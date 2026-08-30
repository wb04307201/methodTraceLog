package cn.wubo.method.trace.log.autoconfigure.otel;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 把 method trace 事件桥接到 OpenTelemetry Span 导出。
 * <p>
 * 设计：
 *  1. 复用 ICallService 钩子。BEFORE → startSpan，AFTER_* → end span。
 *  2. UUID 形式的 traceid/spanid 转 W3C TraceContext 字节序：traceId=UUID(32hex)，
 *     spanId=UUID 前 16hex。
 *  3. 父上下文：用当前 MDC 里的 traceid/pspanid 构造 SpanContext，挂到子 span 的 setParent()。
 *  4. 内部 span 映射 ConcurrentHashMap 兜底清理（10 分钟未关闭视为孤儿）。
 *  5. 容器关闭时由 SimpleOtelAutoConfig 调 close()，flush + shutdown SDK。
 */
@Slf4j
public class SimpleOtelServiceImpl extends AbstractCallService {

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final boolean shutdownOnClose;

    /** spanid → OTel Span 的映射。仅在 BEFORE 时填入，AFTER_* 移除。 */
    private final Map<String, Span> activeSpans = new ConcurrentHashMap<>();

    /** spanid → BEFORE 时刻（毫秒），用于孤儿清理。 */
    private final Map<String, Long> spanBeginTimes = new ConcurrentHashMap<>();

    private static final long ORPHAN_THRESHOLD_MILLIS = 10 * 60 * 1000L;

    public SimpleOtelServiceImpl(OpenTelemetry openTelemetry, MethodTraceLogProperties.OtelProperties props, boolean shutdownOnClose) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(props.getServiceName(), "1.0.0");
        this.shutdownOnClose = shutdownOnClose;
        // 兜底：定期清理未被关闭的 span
        java.util.concurrent.ScheduledExecutorService exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtl-otel-cleanup");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::cleanupOrphans, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void consumer(ServiceCallInfo info) {
        if (info == null || info.getLogActionEnum() == null) {
            return;
        }
        try {
            if (info.getLogActionEnum() == LogActionEnum.BEFORE) {
                startSpan(info);
            } else if (info.getLogActionEnum() == LogActionEnum.AFTER_RETURN
                    || info.getLogActionEnum() == LogActionEnum.AFTER_THROW) {
                endSpan(info);
            }
        } catch (Exception e) {
            // OTEL 故障不能影响业务调用链
            log.warn("mtl-otel: consumer error: {}", e.getMessage());
        }
    }

    private void startSpan(ServiceCallInfo info) {
        Span span;
        try {
            SpanIdContext.set(toOtelSpanIdHex(info.getSpanid()));
            SpanBuilderWrapper builder = newSpanBuilder(info);
            span = builder.start();
        } finally {
            SpanIdContext.clear();
        }
        try (Scope ignored = span.makeCurrent()) {
            // 设置属性
            span.setAttribute(AttributeKey.stringKey("code.namespace"), info.getClassName());
            span.setAttribute(AttributeKey.stringKey("code.function"), info.getMethodName());
            span.setAttribute(AttributeKey.stringKey("code.class.simple"), info.getClassSimpleName());
            // 记录入参
            if (info.getContext() != null) {
                span.setAttribute("mtl.args", String.valueOf(info.getContext()));
            }
        }
        activeSpans.put(info.getSpanid(), span);
        spanBeginTimes.put(info.getSpanid(), info.getTimeMillis());
    }

    private void endSpan(ServiceCallInfo info) {
        Span span = activeSpans.remove(info.getSpanid());
        spanBeginTimes.remove(info.getSpanid());
        if (span == null) {
            return;
        }
        try (Scope ignored = span.makeCurrent()) {
            if (info.getLogActionEnum() == LogActionEnum.AFTER_THROW) {
                span.setStatus(StatusCode.ERROR, "exception");
                // 优先走 rawException 旁路：LogAspect 在写 context 之前调过 transContext(e)
                // 把异常 stringify 了，info.getContext() 不再是 Throwable。
                Throwable raw = info.getRawException();
                if (raw != null) {
                    span.recordException(raw);
                } else if (info.getContext() != null) {
                    span.setAttribute("error.message", String.valueOf(info.getContext()));
                }
            } else {
                if (info.getContext() != null) {
                    span.setAttribute("mtl.result", String.valueOf(info.getContext()));
                }
            }
        } finally {
            // 使用默认 end()：OTel SDK 用调用时刻作为结束时间。
            // duration 在 monitor 服务中由 Micrometer 单独计算。
            span.end();
        }
    }

    /**
     * 包装 OTel SpanBuilder 以便添加 parent context + attributes。
     */
    private SpanBuilderWrapper newSpanBuilder(ServiceCallInfo info) {
        String spanName = info.getClassSimpleName() + "." + info.getMethodName();
        SpanBuilderWrapper wrapper = new SpanBuilderWrapper(tracer.spanBuilder(spanName));
        wrapper.spanBuilder.setSpanKind(SpanKind.INTERNAL);
        // 设置父上下文
        if (info.getPspanid() != null) {
            SpanContext parentCtx = parentContext(info);
            if (parentCtx.isValid()) {
                wrapper.spanBuilder.setParent(Context.current().with(Span.wrap(parentCtx)));
            }
        } else {
            // 根 span
            SpanContext rootCtx = rootContext(info.getTraceid(), info.getSpanid());
            wrapper.spanBuilder.setParent(Context.current().with(Span.wrap(rootCtx)));
        }
        return wrapper;
    }

    /**
     * 构造根调用的“伪父” SpanContext：traceId 用我们自己的 traceid，spanId 用我们自己的 spanid。
     * <p>
     * 必须传一个<b>有效</b>的 spanId：W3C 规范里全 0 的 spanId 是非法值，OTel SDK 会把这样的
     * SpanContext 判为 invalid（等价于“没有父”），从而为每个顶层调用重新生成一个全新的 traceId，
     * 导致 controller span 与其内部 span 落在不同的 OTel trace 里。传入真实 spanid 后，
     * OTel 会沿用我们的 traceId。
     * <p>
     * 已知限制：OTel SDK 仍会为新建的 span 自行生成 spanId，所以导出的 span 的 spanId
     * <b>不会</b>等于 {@code info.getSpanid()}。除非替换 SDK 的 IdGenerator，否则无法规避。
     *
     * @param traceid 本次 trace 的 id（UUID 形式）
     * @param spanid  本次根调用的 span id（UUID 形式），作为伪父 spanId 使用
     */
    private static SpanContext rootContext(String traceid, String spanid) {
        String otelTraceId = toOtelTraceIdHex(traceid);
        String otelSpanId = toOtelSpanIdHex(spanid);
        return SpanContext.create(otelTraceId, otelSpanId, TraceFlags.getSampled(), TraceState.getDefault());
    }

    private static SpanContext parentContext(ServiceCallInfo info) {
        String otelTraceId = toOtelTraceIdHex(info.getTraceid());
        String otelParentId = toOtelSpanIdHex(info.getPspanid());
        return SpanContext.create(otelTraceId, otelParentId, TraceFlags.getSampled(), TraceState.getDefault());
    }

    /**
     * W3C traceparent 中的 traceId 是 16 字节（32 hex）。我们的 traceid 是 UUID 去掉 dash 后
     * 正好 32 hex，直接取全部即可。
     */
    private static String toOtelTraceIdHex(String uuid) {
        if (uuid == null) {
            return "00000000000000000000000000000000";
        }
        String hex = uuid.replace("-", "");
        if (hex.length() < 32) {
            // 长度不足时左补 0 凑齐 32 字符
            return "0".repeat(32 - hex.length()) + hex;
        }
        return hex.substring(0, 32);
    }

    /**
     * W3C traceparent 中的 parent-id 是 8 字节（16 hex）。我们的 spanid 是 UUID 去掉 dash
     * 后 32 hex，取前 16 hex。
     */
    private static String toOtelSpanIdHex(String uuid) {
        if (uuid == null) {
            return "0000000000000000";
        }
        String hex = uuid.replace("-", "");
        if (hex.length() < 16) {
            return "0".repeat(16 - hex.length()) + hex;
        }
        return hex.substring(0, 16);
    }

    private void cleanupOrphans() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (var it = spanBeginTimes.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue() > ORPHAN_THRESHOLD_MILLIS) {
                Span s = activeSpans.remove(e.getKey());
                if (s != null) {
                    try {
                        s.end();
                    } catch (Exception ignore) {
                    }
                }
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("mtl-otel: closed {} orphan spans", removed);
        }
    }

    @Override
    public String getCallServiceName() {
        return "SimpleOtelService";
    }

    @Override
    public String getCallServiceDesc() {
        return "OpenTelemetry OTLP 导出";
    }

    /**
     * 关闭：flush + 关闭 SDK。仅当本类拥有 SDK 生命周期时调用（autoconfigure 决定）。
     */
    public void close() {
        // flush 所有未结束 span
        for (Span s : activeSpans.values()) {
            try {
                s.end();
            } catch (Exception ignore) {
            }
        }
        activeSpans.clear();
        spanBeginTimes.clear();
        if (shutdownOnClose && openTelemetry instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                log.warn("mtl-otel: close failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 内部用 wrapper，让 newSpanBuilder() 能流式设置 SpanBuilder 后返回。
     */
    private static class SpanBuilderWrapper {
        private final io.opentelemetry.api.trace.SpanBuilder spanBuilder;

        SpanBuilderWrapper(io.opentelemetry.api.trace.SpanBuilder spanBuilder) {
            this.spanBuilder = spanBuilder;
        }

        Span start() {
            return spanBuilder.startSpan();
        }
    }
}
