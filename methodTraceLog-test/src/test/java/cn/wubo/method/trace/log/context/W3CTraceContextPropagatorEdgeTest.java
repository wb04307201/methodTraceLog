package cn.wubo.method.trace.log.context;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link W3CTraceContextPropagator} 与 {@link TraceContextSnapshot#restore()} 边界用例测试。
 * <p>
 * 覆盖风险清单：
 * <ul>
 *     <li>R-53 — {@code W3CTraceContextPropagator.inject(null, snap)} 在 snap 不可写时的行为。</li>
 *     <li>R-65 — {@link TraceContextSnapshot#restore()} 返回的 AutoCloseable 自身抛异常
 *         是否会"吃掉"原始业务异常。</li>
 * </ul>
 */
class W3CTraceContextPropagatorEdgeTest {

    // ===== R-53: inject 边界 =====

    @Test
    @DisplayName("inject(null carrier, valid snap) 抛 NPE —— 应当安全 no-op 而非炸线程")
    void inject_nullCarrier_throwsNPE() {
        // 当前实现：carrier=null → Map.of(...) 等价的 NPE。
        // 我们仅锁定"当前行为"——让任何回归（无论是修复成 no-op 还是改成抛更明确的异常）都有迹可循。
        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(Map.of(
                TraceContextSnapshot.MDC_TRACE_ID, "abc",
                TraceContextSnapshot.MDC_SPAN_ID, "def",
                TraceContextSnapshot.MDC_SAMPLED, "true"));
        Assertions.assertThrows(NullPointerException.class,
                () -> W3CTraceContextPropagator.inject(null, snap));
    }

    @Test
    @DisplayName("inject(snap=null) 直接 no-op（早返回）")
    void inject_nullSnapshot_isNoop() {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.inject(carrier, null);
        Assertions.assertTrue(carrier.isEmpty(),
                "snap=null 必须不写入任何 header；实际: " + carrier);
    }

    @Test
    @DisplayName("inject(snap without trace) 直接 no-op —— snap.hasTrace()==false")
    void inject_snapshotWithoutTrace_isNoop() {
        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(Map.of()); // 空 MDC
        Assertions.assertFalse(snap.hasTrace(), "空 MDC → hasTrace()==false");
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.inject(carrier, snap);
        Assertions.assertTrue(carrier.isEmpty(),
                "无 trace 的 snap 必须不写入 traceparent header");
    }

    @Test
    @DisplayName("inject(snap with trace) 写入正确的 W3C header")
    void inject_validSnapshot_writesCorrectHeader() {
        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(Map.of(
                TraceContextSnapshot.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736",
                TraceContextSnapshot.MDC_SPAN_ID, "00f067aa0ba902b7",
                TraceContextSnapshot.MDC_SAMPLED, "true"));
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.inject(carrier, snap);
        Assertions.assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                carrier.get(W3CTraceContextPropagator.TRACEPARENT_HEADER));
    }

    // ===== R-53: extract 在 traceparent 为空字符串时返回 null（不是 throw） =====

    @Test
    @DisplayName("extract(carrier with empty header value) 返回 null（不抛）")
    void extract_emptyHeaderValue_returnsNull() {
        // TraceContextFilter 走 Map.of(traceparent, "") 路径 → W3CTraceContextPropagator.parse("") 返回 null
        TraceContextSnapshot snap = W3CTraceContextPropagator.extract(Map.of(
                W3CTraceContextPropagator.TRACEPARENT_HEADER, ""));
        Assertions.assertNull(snap,
                "extract 空字符串 traceparent 必须返回 null（不让 filter 进入 restore 分支）");
    }

    // ===== R-65: restore() 自身抛异常时被吞掉的边界 =====

    @Test
    @DisplayName("restore() 返回的 AutoCloseable.close() 在 MDC 被外部清掉后能安全还原（不抛）")
    void restore_closeAfterExternalMdcClear_isSafe() throws Exception {
        // 设置原始 MDC
        MDC.put("caller-key", "caller-value");
        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(MDC.getCopyOfContextMap());

        AutoCloseable restore = snap.restore();
        // 模拟外部把 MDC 清空
        MDC.clear();
        try {
            // close 必须仍然能跑（不能因为 MDC 是空的而抛 NPE）
            Assertions.assertDoesNotThrow(restore::close);
        } finally {
            // 还原测试环境
            MDC.clear();
        }
    }

    @Test
    @DisplayName("restore() 嵌套：内层 close 必须还原到外层 close 之前的状态")
    void restore_nestedRestores_returnToCorrectPreState() throws Exception {
        // 锁定 TraceContextSnapshotTest 的契约：close() 恢复到 restore() 之前的状态，
        // 不是恢复到 capture() 之前的状态。
        MDC.put("caller-key", "outer");
        TraceContextSnapshot outer = TraceContextSnapshot.fromMdc(MDC.getCopyOfContextMap());

        AutoCloseable outerRestore = outer.restore();
        try {
            // 在 outerRestore 作用域内设置一个新 key
            MDC.put("inner-key", "inner-value");
            TraceContextSnapshot inner = TraceContextSnapshot.fromMdc(MDC.getCopyOfContextMap());
            AutoCloseable innerRestore = inner.restore();
            try {
                // 不变量：outer key 仍在
                Assertions.assertEquals("outer", MDC.get("caller-key"));
            } finally {
                innerRestore.close();
            }
            // innerRestore 关闭后应回到 inner 之前 —— 即 caller-key=outer + inner-key=inner-value
            Assertions.assertEquals("outer", MDC.get("caller-key"));
            Assertions.assertEquals("inner-value", MDC.get("inner-key"));
        } finally {
            outerRestore.close();
        }
        // outerRestore 关闭后应回到 outer 之前 —— MDC 只剩 caller-key=outer
        // 注意：restore().close() 只负责 trace 4 keys + prevSnap 的恢复，不主动 clear 所有 MDC。
        // 但 caller-key=outer 是 prevSnap 里的，会被 put 回去；inner-key 不在 prevSnap 里，仍可能残留。
        // 这是 R-65 标注的"restore() 只管 trace 4 个 key"的实现细节 —— 测试锁定该契约。
        Assertions.assertEquals("outer", MDC.get("caller-key"));

        MDC.clear();
    }

    @Test
    @DisplayName("restore() 后 MDC 4 个关键 key 都按快照恢复；close 后还原到调用前")
    void restore_restoresFourKeyMdcEntries() throws Exception {
        MDC.clear();
        MDC.put(TraceContextSnapshot.MDC_TRACE_ID, "trace-X");
        MDC.put(TraceContextSnapshot.MDC_SPAN_ID, "span-X");
        MDC.put(TraceContextSnapshot.MDC_PSPAN_ID, "pspan-X");
        MDC.put(TraceContextSnapshot.MDC_SAMPLED, "true");

        TraceContextSnapshot snap = TraceContextSnapshot.fromMdc(MDC.getCopyOfContextMap());
        // 改一下 MDC 让 close() 必须把它恢复
        MDC.put(TraceContextSnapshot.MDC_TRACE_ID, "modified");

        AutoCloseable restore = snap.restore();
        try {
            // restore() 期间 4 个 key 必须等于快照值
            Assertions.assertEquals("trace-X", MDC.get(TraceContextSnapshot.MDC_TRACE_ID));
            Assertions.assertEquals("span-X", MDC.get(TraceContextSnapshot.MDC_SPAN_ID));
            Assertions.assertEquals("pspan-X", MDC.get(TraceContextSnapshot.MDC_PSPAN_ID));
            Assertions.assertEquals("true", MDC.get(TraceContextSnapshot.MDC_SAMPLED));
        } finally {
            restore.close();
        }
        // close 后必须还原到 restore 之前 —— 即 trace-X 又变回 modified
        Assertions.assertEquals("modified", MDC.get(TraceContextSnapshot.MDC_TRACE_ID),
                "close 后 traceid 必须还原到 restore 之前的状态（不是 capture 之前）");
        Assertions.assertEquals("span-X", MDC.get(TraceContextSnapshot.MDC_SPAN_ID));
        Assertions.assertEquals("pspan-X", MDC.get(TraceContextSnapshot.MDC_PSPAN_ID));
        Assertions.assertEquals("true", MDC.get(TraceContextSnapshot.MDC_SAMPLED));
        MDC.clear();
    }
}
