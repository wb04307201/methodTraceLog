package cn.wubo.method.trace.log.context;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class W3CTraceContextPropagatorTest {

    @Test
    void build_and_parse_roundTrip() {
        String traceUuid = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanUuid = "00f067aa0ba902b700f067aa0ba902b7";
        String header = W3CTraceContextPropagator.build(traceUuid, spanUuid, true);
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", header);

        W3CTraceContextPropagator.ParsedTraceParent p = W3CTraceContextPropagator.parse(header);
        assertNotNull(p);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", p.getTraceId());
        assertEquals("00f067aa0ba902b7", p.getParentId());
        assertTrue(p.isSampled());
    }

    @Test
    void parse_withDashless() {
        W3CTraceContextPropagator.ParsedTraceParent p = W3CTraceContextPropagator.parse(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertNotNull(p);
        assertFalse(p.isSampled());
    }

    @Test
    void parse_invalid_returnsNull() {
        assertNull(W3CTraceContextPropagator.parse(null));
        assertNull(W3CTraceContextPropagator.parse(""));
        assertNull(W3CTraceContextPropagator.parse("garbage"));
        // 00 traceId 非法
        assertNull(W3CTraceContextPropagator.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        // 00 spanId 非法
        assertNull(W3CTraceContextPropagator.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"));
    }

    @Test
    void toTraceIdHex_handlesUuid() {
        // 32-char hex (dashless UUID) → 32 char output
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
                W3CTraceContextPropagator.toTraceIdHex("4bf92f35-77b3-4da6-a3ce-929d0e0e4736"));
        // Short → zero-pad
        assertEquals("00000000000000000000000000000001",
                W3CTraceContextPropagator.toTraceIdHex("1"));
    }

    @Test
    void toSpanIdHex_handlesUuid() {
        assertEquals("00f067aa0ba902b7",
                W3CTraceContextPropagator.toSpanIdHex("00f067aa-0ba9-02b7-00f0-67aa0ba902b7"));
        assertEquals("0000000000000001",
                W3CTraceContextPropagator.toSpanIdHex("1"));
    }

    @Test
    void extract_setsMdcFromHeader() {
        TraceContextSnapshot snap = W3CTraceContextPropagator.extract(java.util.Map.of(
                W3CTraceContextPropagator.TRACEPARENT_HEADER,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertNotNull(snap);
        // 注意：traceid 是 32-hex 字符串，不是 UUID 格式
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", snap.getTraceid());
        assertEquals("00f067aa0ba902b7", snap.getPspanid());
        assertEquals("true", snap.getSampled());
    }

    @Test
    void extract_returnsNullWhenAbsent() {
        assertNull(W3CTraceContextPropagator.extract(java.util.Map.of()));
    }
}
