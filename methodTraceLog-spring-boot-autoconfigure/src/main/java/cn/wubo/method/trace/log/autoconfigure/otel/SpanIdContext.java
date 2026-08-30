package cn.wubo.method.trace.log.autoconfigure.otel;

/**
 * ThreadLocal holding the OTel spanId hex to assign to the next span created in this thread.
 * Set by SimpleOtelServiceImpl.startSpan() right before OTel SDK creates the span,
 * read by MtlSpanIdGenerator.generateSpanId().
 */
public final class SpanIdContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private SpanIdContext() {}
    public static void set(String hex) { CURRENT.set(hex); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
