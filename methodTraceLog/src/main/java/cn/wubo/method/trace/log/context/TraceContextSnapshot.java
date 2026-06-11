package cn.wubo.method.trace.log.context;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次 trace 上下文的不可变快照。
 * <p>
 * 用于把当前线程的 trace 上下文（traceid / spanid / pspanid / sampled 以及完整 MDC）
 * 捕获下来，传递到另一线程或跨进程调用，恢复后继续追踪。
 */
public final class TraceContextSnapshot {

    public static final String MDC_TRACE_ID = "traceid";
    public static final String MDC_SPAN_ID = "spanid";
    public static final String MDC_PSPAN_ID = "pspanid";
    public static final String MDC_SAMPLED = "mtlSampled";

    private final String traceid;
    private final String spanid;
    private final String pspanid;
    private final String sampled;
    private final Map<String, String> mdc;

    private TraceContextSnapshot(String traceid, String spanid, String pspanid, String sampled, Map<String, String> mdc) {
        this.traceid = traceid;
        this.spanid = spanid;
        this.pspanid = pspanid;
        this.sampled = sampled;
        this.mdc = mdc;
    }

    /**
     * 从当前线程的 MDC 捕获一份完整快照。返回的快照与当前线程后续修改隔离。
     *
     * @return 当前线程 trace 上下文的不可变快照（包含 traceid/spanid/pspanid/sampled 与完整 MDC）
     */
    public static TraceContextSnapshot capture() {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        if (mdc == null) {
            mdc = new HashMap<>();
        } else {
            mdc = new HashMap<>(mdc);
        }
        return new TraceContextSnapshot(
                mdc.get(MDC_TRACE_ID),
                mdc.get(MDC_SPAN_ID),
                mdc.get(MDC_PSPAN_ID),
                mdc.get(MDC_SAMPLED),
                mdc);
    }

    /**
     * 把快照应用到当前线程的 MDC。返回的 AutoCloseable 关闭时会把当前 MDC 还原为调用前的状态。
     */
    public AutoCloseable restore() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (previous == null) {
            previous = new HashMap<>();
        }
        // 先清掉 4 个关键 key，再恢复完整 MDC（避免残留）
        for (String k : new String[]{MDC_TRACE_ID, MDC_SPAN_ID, MDC_PSPAN_ID, MDC_SAMPLED}) {
            MDC.remove(k);
        }
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            MDC.put(entry.getKey(), entry.getValue());
        }
        final Map<String, String> prevSnap = previous;
        return () -> {
            // 还原为 restore 之前的状态
            for (String k : new String[]{MDC_TRACE_ID, MDC_SPAN_ID, MDC_PSPAN_ID, MDC_SAMPLED}) {
                MDC.remove(k);
            }
            for (Map.Entry<String, String> entry : prevSnap.entrySet()) {
                MDC.put(entry.getKey(), entry.getValue());
            }
        };
    }

    /**
     * 装饰 Runnable，使其执行时携带当前线程的 trace 上下文。
     */
    public Runnable wrap(Runnable delegate) {
        final TraceContextSnapshot snap = this;
        return () -> {
            try (AutoCloseable ignored = snap.restore()) {
                delegate.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * 装饰 Callable。
     */
    public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> delegate) {
        final TraceContextSnapshot snap = this;
        return () -> {
            try (AutoCloseable ignored = snap.restore()) {
                return delegate.call();
            }
        };
    }

    public String getTraceid() {
        return traceid;
    }

    public String getSpanid() {
        return spanid;
    }

    public String getPspanid() {
        return pspanid;
    }

    public String getSampled() {
        return sampled;
    }

    public boolean hasTrace() {
        return traceid != null;
    }

    /**
     * 从一个已填充的 MDC map 直接构造快照。供 W3C propagator 等场景使用。
     */
    public static TraceContextSnapshot fromMdc(Map<String, String> mdc) {
        if (mdc == null) {
            return new TraceContextSnapshot(null, null, null, null, new HashMap<>());
        }
        return new TraceContextSnapshot(
                mdc.get(MDC_TRACE_ID),
                mdc.get(MDC_SPAN_ID),
                mdc.get(MDC_PSPAN_ID),
                mdc.get(MDC_SAMPLED),
                mdc);
    }
}
