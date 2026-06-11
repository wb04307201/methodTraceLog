package cn.wubo.method.trace.log.context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 传播器。
 * <p>
 * 参考 https://www.w3.org/TR/trace-context/
 * <p>
 * traceparent 格式：{@code <version>-<traceId(32hex)>-<parentId(16hex)>-<flags(2hex)>}
 * 例如 {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
 * <p>
 * flags 末位：1 = sampled。
 */
public final class W3CTraceContextPropagator {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";

    private static final Pattern TRACEPARENT_RE = Pattern.compile(
            "^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$");

    private W3CTraceContextPropagator() {
    }

    /**
     * 从 traceparent 头中提取 traceId / spanId / 是否采样。
     * <p>
     * 不合法时返回 null。
     */
    public static ParsedTraceParent parse(String header) {
        if (header == null) {
            return null;
        }
        Matcher m = TRACEPARENT_RE.matcher(header.trim());
        if (!m.matches()) {
            return null;
        }
        String traceId = m.group(2);
        String parentId = m.group(3);
        String flags = m.group(4);
        // traceId / parentId 不能全 0
        if ("00000000000000000000000000000000".equals(traceId)
                || "0000000000000000".equals(parentId)) {
            return null;
        }
        boolean sampled = (Integer.parseInt(flags, 16) & 0x01) == 0x01;
        return new ParsedTraceParent(traceId, parentId, sampled);
    }

    /**
     * 构造一个 traceparent 头，使用我们的 16 字节 traceId（UUID 去掉 dash 的 32 hex）
     * 和 8 字节 spanId（前 16 hex）。
     */
    public static String build(String traceUuid, String spanUuid, boolean sampled) {
        String traceId = toTraceIdHex(traceUuid);
        String spanId = toSpanIdHex(spanUuid);
        String flags = sampled ? "01" : "00";
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    /**
     * 把 trace 上下文快照写到外部载体（Map）。供 RestClient/RestTemplate 拦截器使用。
     */
    public static void inject(java.util.Map<String, String> carrier, TraceContextSnapshot snap) {
        if (snap == null || !snap.hasTrace()) {
            return;
        }
        // spanId 在 W3C 里叫 parent-id
        carrier.put(TRACEPARENT_HEADER, build(snap.getTraceid(), snap.getSpanid(), "true".equals(snap.getSampled())));
    }

    /**
     * 从外部载体中提取（供 server 端使用）。如果 traceparent 存在，返回的 snapshot 已带
     * traceid/spanid/sampled。
     */
    public static TraceContextSnapshot extract(java.util.Map<String, String> carrier) {
        String header = null;
        for (java.util.Map.Entry<String, String> e : carrier.entrySet()) {
            if (TRACEPARENT_HEADER.equalsIgnoreCase(e.getKey())) {
                header = e.getValue();
                break;
            }
        }
        if (header == null) {
            return null;
        }
        ParsedTraceParent p = parse(header);
        if (p == null) {
            return null;
        }
        // traceid / pspanid / sampled 写到 MDC map
        java.util.Map<String, String> mdc = new java.util.HashMap<>();
        mdc.put(TraceContextSnapshot.MDC_TRACE_ID, p.getTraceId());
        mdc.put(TraceContextSnapshot.MDC_PSPAN_ID, p.getParentId());
        mdc.put(TraceContextSnapshot.MDC_SAMPLED, Boolean.toString(p.isSampled()));
        return TraceContextSnapshot.fromMdc(mdc);
    }

    public static String toTraceIdHex(String uuid) {
        if (uuid == null) {
            return "00000000000000000000000000000000";
        }
        String hex = uuid.replace("-", "");
        if (hex.length() < 32) {
            return "0".repeat(32 - hex.length()) + hex;
        }
        return hex.substring(0, 32);
    }

    public static String toSpanIdHex(String uuid) {
        if (uuid == null) {
            return "0000000000000000";
        }
        String hex = uuid.replace("-", "");
        if (hex.length() < 16) {
            return "0".repeat(16 - hex.length()) + hex;
        }
        return hex.substring(0, 16);
    }

    public static class ParsedTraceParent {
        private final String traceId;
        private final String parentId;
        private final boolean sampled;

        public ParsedTraceParent(String traceId, String parentId, boolean sampled) {
            this.traceId = traceId;
            this.parentId = parentId;
            this.sampled = sampled;
        }

        public String getTraceId() {
            return traceId;
        }

        public String getParentId() {
            return parentId;
        }

        public boolean isSampled() {
            return sampled;
        }
    }
}
