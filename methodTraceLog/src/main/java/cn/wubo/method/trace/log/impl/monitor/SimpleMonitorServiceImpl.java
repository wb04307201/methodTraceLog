package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.store.ITraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cn.wubo.method.trace.log.Constants.*;

/**
 * 监控服务：写 Micrometer Timer + 委托 {@link ITraceStore} 存储方法调用树。
 * <p>
 * 设计要点：
 *  1. trace 持久化抽象到 ITraceStore；本类只维护 span 级别瞬时状态。
 *  2. timerSamples / methodTraceInfoMap 在 BEFORE 之后可能永远没有 AFTER 事件
 *     （业务异常、ICallService 链中抛错等），需要兜底清理。
 *  3. 根节点在 AFTER 之后再调一次 traceStore.save()，让 store 看到完整的 after。
 *  4. 定期清理：孤儿 span（10 min 阈值）+ 过期 trace（ttlMillis）。
 *  5. AFTER 事件里 timerSamples.get(...) 可能为 null，加防御。
 */
@Slf4j
public class SimpleMonitorServiceImpl extends AbstractCallService {

    private final MeterRegistry meterRegistry;
    private final ITraceStore traceStore;
    private final long maxAgeMillis;

    /**
     * 构造方法。
     *
     * @param meterRegistry Micrometer 注册表（写入 Timer）
     * @param traceStore    trace 持久化后端
     * @param maxAgeMillis  过期阈值（毫秒），传给 {@code traceStore.clean()} 定期清理
     */
    public SimpleMonitorServiceImpl(MeterRegistry meterRegistry, ITraceStore traceStore, long maxAgeMillis) {
        this.meterRegistry = meterRegistry;
        this.traceStore = traceStore;
        this.maxAgeMillis = maxAgeMillis;
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtl-monitor-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleAtFixedRate(this::cleanupOrphans, 60, 60, TimeUnit.SECONDS);
        cleanup.scheduleAtFixedRate(() -> traceStore.clean(maxAgeMillis), 5, 5, TimeUnit.MINUTES);
    }

    private final Map<String, Timer.Sample> timerSamples = new ConcurrentHashMap<>();

    private final Map<String, MethodTraceInfo> methodTraceInfoMap = new ConcurrentHashMap<>();

    /** 记录每个 span 起始时间，用于孤儿清理。value 是 begin timeMillis。 */
    private final Map<String, Long> spanBeginTimes = new ConcurrentHashMap<>();

    private static final long ORPHAN_THRESHOLD_MILLIS = 10 * 60 * 1000L; // 10 分钟

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.BEFORE) {
            Timer.Sample sample = Timer.start(meterRegistry);
            timerSamples.put(serviceCallInfo.getSpanid(), sample);
            spanBeginTimes.put(serviceCallInfo.getSpanid(), serviceCallInfo.getTimeMillis());

            MethodTraceInfo methodTraceInfo = MethodTraceInfo.create(serviceCallInfo);
            methodTraceInfoMap.put(serviceCallInfo.getSpanid(), methodTraceInfo);
            if (serviceCallInfo.getPspanid() == null) {
                // 根节点：先在 store 中占位（BEFORE 阶段，让列表立即可见）
                traceStore.save(methodTraceInfo);
            } else {
                MethodTraceInfo parent = methodTraceInfoMap.get(serviceCallInfo.getPspanid());
                if (parent != null) {
                    parent.addChild(methodTraceInfo);
                }
            }
        } else if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_RETURN
                || serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW) {
            // 防御：ICallService 链中途抛错可能让某些 span 永远等不到 AFTER
            Timer.Sample sample = timerSamples.remove(serviceCallInfo.getSpanid());
            spanBeginTimes.remove(serviceCallInfo.getSpanid());
            if (sample != null) {
                sample.stop(Timer.builder(METHOD_EXECUTION_TIME)
                        .tags(CLASS_NAME, serviceCallInfo.getClassName(),
                                METHOD_SIGNATURE, serviceCallInfo.getMethodSignatureLongString(),
                                ACTION, serviceCallInfo.getLogActionEnum().name())
                        .register(meterRegistry));
            }

            MethodTraceInfo methodTraceInfo = methodTraceInfoMap.remove(serviceCallInfo.getSpanid());
            if (methodTraceInfo != null) {
                methodTraceInfo.end(serviceCallInfo);
                // 根节点再次写入 store，让 store 看到 after 字段
                if (methodTraceInfo.getBefore() != null && methodTraceInfo.getBefore().getPspanid() == null) {
                    traceStore.save(methodTraceInfo);
                }
            }
        }
    }

    /**
     * 兜底清理：任何 span 开始 10 分钟之后还没收到 AFTER 事件，认为孤儿。
     * 同时清掉 store 中过期的根 trace。
     */
    private void cleanupOrphans() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = spanBeginTimes.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > ORPHAN_THRESHOLD_MILLIS) {
                String spanid = e.getKey();
                timerSamples.remove(spanid);
                methodTraceInfoMap.remove(spanid);
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("mtl-monitor: cleaned {} orphan spans", removed);
        }
    }

    @Override
    public String getCallServiceName() {
        return "SimpleMonitorService";
    }

    @Override
    public String getCallServiceDesc() {
        return "监控指标";
    }

    /**
     * 通过 traceStore 按 traceId 查根节点。
     *
     * @param id traceId
     * @return 根节点；找不到返回 null
     */
    public MethodTraceInfo getByTraceId(String id) {
        return traceStore.getByTraceId(id);
    }

    /**
     * 全部根 trace（不过滤，limit=1000）。供"最近 8 小时"等面板用。
     *
     * @return 根 trace 列表，按完成时间倒序
     */
    public List<MethodTraceInfo> getMethodTraceInfos() {
        return getMethodTraceInfos(null, null, false, 1000);
    }

    /**
     * 过滤 + 截断后的根 trace 列表。
     *
     * @param classNamePattern  类名 substring 匹配（不区分大小写），null 不过滤
     * @param methodNamePattern 方法名 substring 匹配（不区分大小写），null 不过滤
     * @param onlyErrors        true 时只保留 AFTER_THROW 的根 trace
     * @param limit             最多返回多少条
     * @return 过滤后的根 trace 列表，按 store 返回顺序截断到 {@code limit}
     */
    public List<MethodTraceInfo> getMethodTraceInfos(String classNamePattern, String methodNamePattern, boolean onlyErrors, int limit) {
        List<MethodTraceInfo> all = traceStore.getRecent(onlyErrors ? 5000 : 2000);
        String cn = classNamePattern == null ? null : classNamePattern.toLowerCase();
        String mn = methodNamePattern == null ? null : methodNamePattern.toLowerCase();
        List<MethodTraceInfo> filtered = new java.util.ArrayList<>();
        for (MethodTraceInfo info : all) {
            if (info == null || info.getBefore() == null) {
                continue;
            }
            if (cn != null && (info.getBefore().getClassName() == null || !info.getBefore().getClassName().toLowerCase().contains(cn))) {
                continue;
            }
            if (mn != null && (info.getBefore().getMethodName() == null || !info.getBefore().getMethodName().toLowerCase().contains(mn))) {
                continue;
            }
            if (onlyErrors) {
                if (info.getAfter() == null || info.getAfter().getLogActionEnum() != LogActionEnum.AFTER_THROW) {
                    continue;
                }
            }
            filtered.add(info);
            if (filtered.size() >= limit) {
                break;
            }
        }
        return filtered;
    }
}
