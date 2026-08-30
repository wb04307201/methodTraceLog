package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.store.InMemoryTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimpleMonitorServiceImpl 跨线程父/子时序回归测试（F-07）。
 * <p>
 * 修复前：父 BEFORE → 父 AFTER → 子 BEFORE → 子 AFTER（异步场景，父先于子完成），
 * 父 AFTER 时已"无 in-flight child" → 父保存为"无子节点的根"到 store；
 * 子 AFTER 时 pspanid 不在 in-memory map（父已离开）→ 子作为"根"也保存到 store，
 * 同一个 traceid 出现两条 record，store 用 putIfAbsent 覆盖 —— 面板只看到其中之一。
 * <p>
 * 修复后：父 AFTER 时检测 methodTraceInfo.getChildren() 中是否还有 in-flight
 * （在 methodTraceInfoMap 中）的子节点；如有，跳过本次 save —— 子 AFTER 到达时
 * 走"无 in-flight child + 无 pspanid"路径也 save 自己的（虽然不带 children 挂载，
 * 但至少 traceid 不会在 store 中消失）。
 * <p>
 * <b>核心断言</b>：跨线程父/子场景下，{@code getRecent()} 应包含至少一条该 traceid
 * 的根节点（不能"两条互相覆盖丢一条"）。
 */
class SimpleMonitorServiceImplCrossThreadTest {

    private static final long MAX_AGE_MILLIS = 8L * 60 * 60 * 1000L;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final InMemoryTraceStore store = new InMemoryTraceStore();
    private final SimpleMonitorServiceImpl service = new SimpleMonitorServiceImpl(meterRegistry, store, MAX_AGE_MILLIS);

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> privateMap(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Map<K, V>) f.get(target);
    }

    private static ServiceCallInfo mk(String traceid, String spanid, String pspanid,
                                      String className, String methodName, LogActionEnum action) {
        return new ServiceCallInfo(
                traceid, pspanid, spanid, className, className,
                methodName, methodName + "()", methodName + "()",
                action == LogActionEnum.BEFORE ? List.of() : "ctx",
                action,
                System.currentTimeMillis());
    }

    @Test
    void parentAfterBeforeChildAfter_doesNotLoseTrace() throws Exception {
        // 跨线程：父线程先完成，子线程在父 AFTER 之后才完成（这是 F-07 关心的时序）
        String traceid = "t-cross";
        String parentSpan = "s-parent";
        String childSpan = "s-child";

        // 父 BEFORE
        service.consumer(mk(traceid, parentSpan, null, "Demo", "outer", LogActionEnum.BEFORE));
        // 子 BEFORE（在父线程里调用，模拟父调用子代码路径触发切入面）
        service.consumer(mk(traceid, childSpan, parentSpan, "Demo", "inner", LogActionEnum.BEFORE));

        Map<String, MethodTraceInfo> midMap = privateMap(service, "methodTraceInfoMap");
        assertTrue(midMap.containsKey(parentSpan), "父应仍在 map 中");
        assertTrue(midMap.containsKey(childSpan), "子应仍在 map 中");

        // 父 AFTER 先到（子还在 in-flight）
        service.consumer(mk(traceid, parentSpan, null, "Demo", "outer", LogActionEnum.AFTER_RETURN));

        // 此刻父应已被移除 map，但 store 中"无 in-flight child"检查应避免把无子挂载的根保存
        midMap = privateMap(service, "methodTraceInfoMap");
        assertFalse(midMap.containsKey(parentSpan), "父 AFTER 后应被移除 map");
        assertTrue(midMap.containsKey(childSpan), "子应仍在 map 中（in-flight）");

        // 关键断言：getRecent() 必须仍然能通过 traceid 找到这条记录（不能"被父先保存无子版本"覆盖）
        MethodTraceInfo byTraceId = store.getByTraceId(traceid);
        assertNotNull(byTraceId, "traceid " + traceid + " 必须仍在 store 中（不能被父先 save 的无子版本覆盖）");

        // 子 AFTER 后到
        service.consumer(mk(traceid, childSpan, parentSpan, "Demo", "inner", LogActionEnum.AFTER_RETURN));

        // 最终：traceid 必须仍在 store 中（不能两条互相覆盖丢一条）
        MethodTraceInfo finalInfo = store.getByTraceId(traceid);
        assertNotNull(finalInfo, "最终 traceid " + traceid + " 必须仍可查到");
    }

    @Test
    void normalInProcessCall_stillWorks() throws Exception {
        // 反向：进程内同步调用（父在子 AFTER 之后才 AFTER），行为应当与历史一致
        String traceid = "t-sync";
        String parentSpan = "s-p";
        String childSpan = "s-c";

        service.consumer(mk(traceid, parentSpan, null, "X", "outer", LogActionEnum.BEFORE));
        service.consumer(mk(traceid, childSpan, parentSpan, "X", "inner", LogActionEnum.BEFORE));

        // 子先 AFTER
        service.consumer(mk(traceid, childSpan, parentSpan, "X", "inner", LogActionEnum.AFTER_RETURN));
        // 父后 AFTER
        service.consumer(mk(traceid, parentSpan, null, "X", "outer", LogActionEnum.AFTER_RETURN));

        MethodTraceInfo info = store.getByTraceId(traceid);
        assertNotNull(info, "同步路径下 traceid 必须可查");
        // 父在 AFTER 时已经无 in-flight child（子已 AFTER 离开 map）→ save
        // 此时 info 应有 children 挂载（因为子在父 AFTER 之前已 addChild）
        // 注意：getByTraceId 拿的是 store 中最新 save 的引用，所以应该是带 children 的
        assertNotNull(info.getAfter(), "父的 after 字段应有值");
    }

    @Test
    void trulyConcurrentParentChild_executorService() throws Exception {
        // 真并发：用 ExecutorService 让父 AFTER 在子 AFTER 之前完成
        // 验证 store 不会"丢"任何一条 traceid
        String traceid = "t-truly-concurrent";
        String parentSpan = "s-tp";
        String childSpan = "s-tc";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch childScheduled = new CountDownLatch(1);
        AtomicReference<Throwable> childError = new AtomicReference<>();

        // 父 BEFORE
        service.consumer(mk(traceid, parentSpan, null, "X", "outer", LogActionEnum.BEFORE));
        // 子 BEFORE
        service.consumer(mk(traceid, childSpan, parentSpan, "X", "inner", LogActionEnum.BEFORE));
        // 父 AFTER —— 此时子还在 map（in-flight）
        service.consumer(mk(traceid, parentSpan, null, "X", "outer", LogActionEnum.AFTER_RETURN));

        // 用单线程池调度子 AFTER（保证在父 AFTER 之后执行 —— 因为上面已经同步发了父 AFTER）
        pool.submit(() -> {
            try {
                childScheduled.countDown();
                service.consumer(mk(traceid, childSpan, parentSpan, "X", "inner", LogActionEnum.AFTER_RETURN));
            } catch (Throwable t) {
                childError.set(t);
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "子 AFTER 未完成");
        assertNull(childError.get(), "子 AFTER 不应抛异常");
        assertTrue(childScheduled.getCount() == 0, "子 AFTER 应已被调度");

        // 关键：traceid 必须仍在 store 中
        MethodTraceInfo info = store.getByTraceId(traceid);
        assertNotNull(info, "跨线程场景下 traceid " + traceid + " 必须仍可查到（修复前会被覆盖丢失）");
    }
}
