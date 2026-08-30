package cn.wubo.method.trace.log.alerting;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异常告警 {@code ICallService}：只关心 {@link LogActionEnum#AFTER_THROW} 事件。
 * <p>
 * 每个 {@code className#methodName} 维护一个滑动窗口（时间戳队列）。窗口内错误数达到
 * {@code threshold.errorCount} 时产生一条 {@link AlertEvent}，压入固定容量 ring buffer，
 * 并（若配置了 webhookUrl）异步 best-effort POST 出去。触发后进入 {@code cooldownSeconds}
 * 抑制期，期间同一方法的越界不再重复告警，避免抖动风暴。
 * <p>
 * webhook 失败只打 warn，绝不向调用方（也就是被拦截的业务方法）抛异常。
 */
@Slf4j
public class AlertingService extends AbstractCallService {

    /** 告警事件类型：滑动窗口错误数越界。 */
    public static final String TYPE_ERROR_THRESHOLD = "error_threshold";

    /** ring buffer 容量，超出丢弃最旧。 */
    private static final int MAX_RECENT = 100;

    /** sampleError 截断长度。 */
    private static final int MAX_SAMPLE_ERROR = 500;

    private final MethodTraceLogProperties.AlertingProperties props;
    private final RestClient webhookClient;
    private final Clock clock;

    /** 最近告警事件（头部最新）。 */
    private final Deque<AlertEvent> recent = new ConcurrentLinkedDeque<>();

    /** {@code className#methodName} → 窗口内错误时间戳队列。 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /** {@code className#methodName} → 上次告警时间戳（cooldown 起点）。 */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    /**
     * 专用 webhook 投递线程池。daemon + cached：不阻塞 Tomcat 请求线程，每个 webhook 单独跑。
     * 之所以不能直接用 RestClient 同步调：JDK HttpClient 默认无限超时，
     * 当 webhook URL 指向 host 自身时会产生「所有 Tomcat 线程都在等自己回 webhook 请求」的活锁。
     */
    private static final ExecutorService WEBHOOK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mtl-alerting-webhook");
        t.setDaemon(true);
        return t;
    });

    public AlertingService(MethodTraceLogProperties.AlertingProperties props, RestClient webhookClient, Clock clock) {
        this.props = props;
        this.webhookClient = webhookClient;
        this.clock = clock;
    }

    /**
     * 累计窗口 → 检查阈值 → 检查 cooldown → 构造事件 → 入 ring buffer → 发 webhook。
     *
     * @param info 方法调用事件；非 {@code AFTER_THROW} 直接忽略
     */
    @Override
    public void consumer(ServiceCallInfo info) {
        if (!props.isEnable() || info == null || info.getLogActionEnum() != LogActionEnum.AFTER_THROW) return;
        if (!matchesClassFilter(info.getClassName())) return;

        String key = info.getClassName() + "#" + info.getMethodName();
        long now = clock.millis();
        long windowMs = props.getThreshold().getWindowSeconds() * 1000L;

        // 1. push 到窗口并裁剪掉滑出窗口的旧时间戳
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }

        // 2. 未跨阈值不告警
        if (q.size() < props.getThreshold().getErrorCount()) return;

        // 3. cooldown 检查（原子版）：用 putIfAbsent 而不是 get + put。
        //    高并发下 get + put 之间会被多个线程同时进入临界区，
        //    导致 N 个线程都通过 "now - last >= cooldownMs" 检查、各自 put 各自己的时间戳、
        //    各自发 webhook —— 抑制失效。
        //    putIfAbsent 返回旧值：null = 之前没记录过（首次进入），通过；
        //    非 null = 已经被别的线程抢到了，本次直接放弃。
        long cooldownMs = props.getCooldownSeconds() * 1000L;
        Long prior = cooldownUntil.putIfAbsent(key, now);
        if (prior != null) {
            // 已被别的线程占位；只有在 cooldown 真正过期后才放行。
            if (now - prior < cooldownMs) return;
            // cooldown 边界：尝试覆盖为本次时间戳（其它线程可能已经覆盖了 → 接受失败）
            if (!cooldownUntil.replace(key, prior, now)) return;
        }

        // 4. 构造事件
        // 优先用原始 Throwable（info.getRawException() —— LogAspect 已经在
        // LogAspect.java:220 写入），调 toString() 拿到完整 stacktrace；
        // 没有 rawException 时（极少数场景，例如直接构造 ServiceCallInfo 喂进来）
        // 才退回 String.valueOf(transContext(context))。
        String errorText = info.getRawException() != null
                ? stackTraceToString(info.getRawException())
                : String.valueOf(transContext(info.getContext()));
        AlertEvent event = new AlertEvent(
                UUID.randomUUID().toString(),
                now,
                TYPE_ERROR_THRESHOLD,
                info.getClassName(),
                info.getMethodName(),
                info.getTraceid(),
                q.size(),
                props.getThreshold().getWindowSeconds(),
                truncate(errorText, MAX_SAMPLE_ERROR));

        // 5. 入 ring buffer + 6. 发 webhook（失败不抛）
        trigger(event);
    }

    /**
     * 把 Throwable 的类名 + message + 完整 stacktrace 拼成单字符串。
     * 不同于 {@link AbstractCallService#transContext(Object)} 截断到 10 行 + 换行格式，
     * 告警需要全栈以便运维快速定位。
     */
    private static String stackTraceToString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        sb.append('\n');
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\tat ").append(el).append('\n');
        }
        return sb.toString();
    }

    /**
     * 白名单前缀匹配。{@code classes} 为 null / empty 时全部放行。
     *
     * @param className 被拦截的类全限定名，可能为 null
     * @return true 表示该类需要告警
     */
    private boolean matchesClassFilter(String className) {
        List<String> classes = props.getClasses();
        if (classes == null || classes.isEmpty()) return true;
        return classes.stream().anyMatch(p -> className != null && className.startsWith(p));
    }

    /**
     * best-effort webhook POST。**异步 + 3s 超时**，绝不阻塞 consumer() 调用方。
     * <p>
     * 历史教训：用 RestClient 同步调 + JDK HttpClient 默认无限超时，
     * 当 webhook URL 指向 host 自身时高并发会把所有 Tomcat 请求线程活锁（Phase A 发现）。
     * 现在投递跑在专用 daemon 线程池上，3s 超时后丢弃，业务路径 0 阻塞。
     *
     * @param event 待推送的事件
     */
    private void sendWebhook(AlertEvent event) {
        String url = props.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("[alerting] {}#{} crossed threshold (count={}), no webhook configured",
                    event.getClassName(), event.getMethodName(), event.getErrorCount());
            return;
        }
        WEBHOOK_EXECUTOR.execute(() -> doPostWebhook(url, event));
    }

    /**
     * 实际 POST。在 daemon 线程池中跑。任何异常 / 超时都只记 warn，绝不向上抛。
     */
    private void doPostWebhook(String url, AlertEvent event) {
        try {
            webhookClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("[alerting] webhook POST to {} failed: {}", url, e.getMessage());
        }
    }

    /**
     * 取最近 N 条告警事件（最新的在前）。
     *
     * @param limit 最多返回条数；≤0 返回空 list
     * @return 不可变顺序的新 list，永不为 null
     */
    public List<AlertEvent> getRecent(int limit) {
        int n = Math.max(0, Math.min(limit, recent.size()));
        List<AlertEvent> out = new ArrayList<>(n);
        Iterator<AlertEvent> it = recent.iterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        return out;
    }

    /**
     * 直接投递一条事件：入 ring buffer + 发 webhook。绕过窗口/cooldown 判定，
     * 供内部（{@link #consumer}）与测试/其它告警来源（如慢方法）复用。
     *
     * @param event 事件，不能为 null
     */
    public void trigger(AlertEvent event) {
        if (event == null) return;
        recent.addFirst(event);
        while (recent.size() > MAX_RECENT) recent.pollLast();
        sendWebhook(event);
    }

    /**
     * 截断长文本，超长时加省略号，避免 webhook body 过大。
     *
     * @param s   原文，可能为 null
     * @param max 最大长度
     * @return 截断后的文本；入参为 null 时返回 null
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override
    public String getCallServiceName() {
        return "AlertingService";
    }

    @Override
    public String getCallServiceDesc() {
        return "异常告警";
    }
}
