package cn.wubo.method.trace.log.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextSnapshotTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void capture_emptyMdc_returnsEmptySnapshot() {
        MDC.clear();
        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        assertNull(snap.getTraceid());
        assertFalse(snap.hasTrace());
    }

    @Test
    void capture_and_restore_roundTrip() throws Exception {
        MDC.put("traceid", "abc-123");
        MDC.put("spanid", "span-1");
        MDC.put("pspanid", "parent-1");
        MDC.put("mtlSampled", "true");
        MDC.put("custom", "value");

        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        assertEquals("abc-123", snap.getTraceid());
        assertEquals("span-1", snap.getSpanid());
        assertEquals("parent-1", snap.getPspanid());
        assertEquals("true", snap.getSampled());

        // 改当前 MDC
        MDC.put("traceid", "DIFFERENT");

        try (AutoCloseable ignored = snap.restore()) {
            assertEquals("abc-123", MDC.get("traceid"));
            assertEquals("span-1", MDC.get("spanid"));
            assertEquals("value", MDC.get("custom"));
        }
        // 关闭后还原
        assertEquals("DIFFERENT", MDC.get("traceid"));
    }

    @Test
    void restore_overwritesExistingMdc() throws Exception {
        MDC.put("traceid", "before");
        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        MDC.put("traceid", "after");
        try (AutoCloseable ignored = snap.restore()) {
            assertEquals("before", MDC.get("traceid"));
        }
        assertEquals("after", MDC.get("traceid"));
    }

    @Test
    void wrap_runnable_propagatesToAnotherThread() throws Exception {
        MDC.put("traceid", "t-1");
        MDC.put("spanid", "s-1");
        MDC.put("mtlSampled", "true");

        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        // 模拟切到新线程：当前线程没有 MDC
        MDC.clear();

        AtomicReference<String> seen = new AtomicReference<>();
        Runnable wrapped = snap.wrap(() -> seen.set(MDC.get("traceid")));
        wrapped.run();
        assertEquals("t-1", seen.get());
    }

    @Test
    void wrap_callable_propagatesToAnotherThread() throws Exception {
        MDC.put("traceid", "t-2");
        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>();
        java.util.concurrent.Callable<String> wrapped = snap.wrap(() -> {
            seen.set(MDC.get("traceid"));
            return "ok";
        });
        assertEquals("ok", wrapped.call());
        assertEquals("t-2", seen.get());
    }

    @Test
    void fromMdc_buildsSnapshot() {
        java.util.Map<String, String> mdc = new java.util.HashMap<>();
        mdc.put(TraceContextSnapshot.MDC_TRACE_ID, "t");
        mdc.put(TraceContextSnapshot.MDC_SAMPLED, "true");
        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(mdc);
        assertEquals("t", snap.getTraceid());
        assertEquals("true", snap.getSampled());
        assertTrue(snap.hasTrace());
    }
}
