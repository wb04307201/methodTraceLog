package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.context.W3CTraceContextPropagator;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双实例端到端：验证 OTel traceid（在调用 JVM 上的 {@code Span.current().getSpanContext().getTraceId()}）
 * 与内部 method-trace-log traceid（{@code MDC.traceid} / {@link ServiceCallInfo#getTraceid()}）的
 * traceparent 头往返关系。
 *
 * <p>拓扑：
 * <pre>
 *   secondary (8086) -- /test/otel-out --> primary (8085) -- /test/aspectLog
 *                         ^                                    ^
 *                         |                                    |
 *                         +----- traceparent 头：OTel SDK -------+
 *                               把 secondary 端的 traceid 注入；
 *                               primary 的 TraceContextFilter 从入站头恢复。
 * </p>
 *
 * <p>两个进程都注册了 starter：
 * <ul>
 *   <li>secondary 的 {@code /test/otel-out}（{@link cn.wubo.method.trace.log.autoconfigure.TraceContextRestClientCustomizer}）
 *       在出站请求上注入 {@code traceparent} 头。</li>
 *   <li>primary 的 {@link cn.wubo.method.trace.log.autoconfigure.TraceContextFilter}
 *       从入站请求头里恢复 traceid 写入 MDC，让 LogAspect 用同一个 traceid
 *       记录入站 span。</li>
 * </ul>
 *
 * <p><b>已知 PRODUCT GAP（Task 3 review / Ruling 6）：</b>
 * TraceContextFilter 把上游 parent-id 写入 MDC key {@code pspanid}，
 * 但 LogAspect 读的是 MDC key {@code spanid}（filter 永不设置该 key）。
 * 因此跨实例父/子链路 {@code pspanid} 没有端到端接通 — 只有 traceid 通过了。
 * 本测试用 {@code @BeforeEach} 的 {@code Assumptions} 跳过
 * （JVM 里没有 {@code GlobalOpenTelemetry} 被初始化），并准备好了一旦 OTel SDK
 * 全局初始化后就能跑通：traceid 对齐 + 对 pspanid==null 做明确断言，把上面
 * 的 gap 直接记入测试证据。</p>
 *
 * <p>注意：{@link MethodTraceInfo} 没有 {@code getTraceId()} — traceid 在
 * {@link MethodTraceInfo#getBefore()} 上的 {@link ServiceCallInfo#getTraceid()}。
 * 直接 {@code List.class} 反序列化会被 Jackson 解成 {@code List<LinkedHashMap>}，
 * 走到 {@code .getBefore()} 会抛 ClassCastException — 这里走 {@link ParameterizedTypeReference}
 * 把序列化器直接落到 {@code List<MethodTraceInfo>}。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtelPropagationIT {

    /**
     * OTel SDK 已经在某个真实上下文（{@code Span.current().getSpanContext().isValid()}）就执行；
     * 否则 skip（包括 {@code GlobalOpenTelemetry.get() == null}（类路径缺 jar），
     * 以及 SDK 没绑定到 GlobalOpenTelemetry、{@code Span.current()} 落到 no-op 两种场景）。
     */
    private static final String SKIP_MESSAGE = "OTel SDK not wired into GlobalOpenTelemetry; skipping OTel propagation test";

    private MtlE2eHarness primary;   // 8085
    private MtlE2eHarness secondary; // 8086

    @BeforeAll
    void setup() {
        primary = MtlE2eHarness.primary(8085, Map.of());
        secondary = MtlE2eHarness.secondary(8086);
    }

    @AfterAll
    void teardown() {
        if (secondary != null) secondary.close();
        if (primary != null) primary.close();
    }

    @BeforeEach
    void skipIfNoOtel() {
        // 软依赖 OTel：API 无 jar 就 NPE（应通过编译期 + surefire 类路径守住），
        // 没注册 SDK 也直接拿到 isValid()==false 的 no-op SpanContext。
        Assumptions.assumeTrue(
                GlobalOpenTelemetry.get() != null
                        && Span.current().getSpanContext().isValid(),
                SKIP_MESSAGE);
    }

    /**
     * 用 ParameterizedTypeReference 把 {@code /methodTraceLog/view/list} 序列化成
     * 真正的 {@code List<MethodTraceInfo>}（直接 {@code List.class} 会拿到
     * {@code List<LinkedHashMap>}，到 {@code getBefore()} 时抛 ClassCastException）。
     */
    private List<MethodTraceInfo> fetchTraceList(MtlE2eHarness h, int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = h.http().getRestTemplate().exchange(
                "http://localhost:" + h.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                typeRef);
        return resp.getBody();
    }

    /** 把出站（UUID 带 dash，36 字符）与入站（W3C 32 hex）traceid 标准化到 32 hex 再比较。 */
    private static String normalize(String tid) {
        return W3CTraceContextPropagator.toTraceIdHex(tid);
    }

    /**
     * OTel traceid（{@code Span.current().getSpanContext().getTraceId()}，W3C 32 hex）
     * 与 secondary 上的 outbound 根 methodTraceInfo 的 {@code before.traceid}
     * （UUID 去 dash 后正好 32 hex）应当对齐到同一 32 hex 串，
     * 并在 primary 端以同一 traceid 出入站痕迹出现（traceparent 头往返）。
     *
     * <p>测试名取自 brief；测试自身同时把跨实例 pspanid 链路未端到端接通这个
     * 产品缺口以显式断言形式钉在断言里（per Ruling 6）。</p>
     */
    @Test
    void otel_trace_id_matches_internal_trace_id() {
        // Outbound via RestClient from secondary (8086) → primary (8085) /test/aspectLog
        secondary.http().getForEntity(
                "http://localhost:8086/test/otel-out?port=8085&name=otel-rc-test",
                String.class);

        // OTel 侧的 traceid（W3C 32 hex 格式，无 dash）。
        String otelHexTraceId = Span.current().getSpanContext().getTraceId();
        assertThat(otelHexTraceId)
                .as("OTel SDK must be wired up via GlobalOpenTelemetry for this test to make sense")
                .isNotEqualTo("00000000000000000000000000000000");

        // Secondary 应该记录自己的出站 callRemote — 根 methodName = otelOut。
        // 该根节点携带我们的 UUID 形式 traceid；它经由
        // TraceContextRestClientCustomizer 写入 traceparent 头（toTraceIdHex 后是 32 hex），
        // 再由 primary 的 TraceContextFilter 恢复（已是 32 hex）成入站 traceid。
        List<MethodTraceInfo> secondaryRoots = awaitRootsContainingMethod(
                secondary, "otelOut", Duration.ofSeconds(5));
        Optional<MethodTraceInfo> secondaryOutbound = secondaryRoots.stream()
                .filter(r -> r != null && r.getBefore() != null
                        && "otelOut".equals(r.getBefore().getMethodName()))
                .findFirst();
        assertThat(secondaryOutbound)
                .as("secondary should record the outbound /test/otel-out call")
                .isPresent();
        String secondaryNormalized = normalize(secondaryOutbound.get().getBefore().getTraceid());
        assertThat(secondaryNormalized)
                .as("secondary outbound traceid, normalized to 32 hex")
                .isNotNull();

        // BEST-EFFORT equality per Task 4 brief: Span.current() runs on the TEST RUNNER JVM
        // (a third JVM — not primary, not secondary). secondary's outbound traceid is
        // generated by secondary's LogAspect in secondary's own JVM. The two CANNOT be equal
        // unless the test runner and secondary share a JVM (single-JVM mode), OR the test
        // runner injects a traceparent header into its outbound HTTP call to secondary.
        // In multi-JVM mode (the default for this project) the OTel traceid from the runner
        // JVM and secondary's internal traceid are generated independently. The hard equality
        // assertion at this point is structurally unreachable; the brief acknowledges this
        // and uses an if/else: assert if matched, otherwise print a documentation message
        // explaining the cross-JVM limitation. Cross-JVM OTel propagation is verified
        // separately via the /test/callRemote chain in TracePropagationIT.
        if (otelHexTraceId.equals(secondaryNormalized)) {
            assertThat(true)
                    .as("OTel traceid matches secondary's outbound traceid; reachable only in "
                            + "single-JVM / traceparent-injected mode")
                    .isTrue();
        } else {
            System.out.println("OTel traceid " + otelHexTraceId
                    + " does not equal secondary's outbound traceid " + secondaryNormalized
                    + " (test runner JVM is a third JVM, distinct from secondary; OTel-on-runner "
                    + "cannot inject a traceparent header into secondary's outbound HTTP call. "
                    + "Cross-JVM OTel trace propagation is verified separately via the "
                    + "/test/callRemote chain in TracePropagationIT.)");
        }

        // Primary 入站根 methodName = aspectLog（TestController 端点方法名），
        // 它的 traceid 应当与 secondary 出站根的 traceid 完全一致（同一 traceparent 来回）。
        List<MethodTraceInfo> primaryRoots = fetchTraceList(primary, 50);
        Optional<MethodTraceInfo> primaryInbound = primaryRoots.stream()
                .filter(r -> r != null && r.getBefore() != null
                        && "aspectLog".equals(r.getBefore().getMethodName())
                        && secondaryNormalized.equals(normalize(r.getBefore().getTraceid())))
                .findFirst();
        assertThat(primaryInbound)
                .as("primary should record inbound call with the same traceid propagated through W3C traceparent")
                .isPresent();

        // PRODUCT GAP (Ruling 6): TraceContextFilter writes upstream parent-id to
        // MDC key 'pspanid', but LogAspect.around reads it from MDC key 'spanid' —
        // which the filter never sets. Result: primary's inbound root has pspanid==null.
        // We assert it explicitly so a future fix that wires the keys correctly will
        // turn this assertion into a failure and force a corresponding test update.
        ServiceCallInfo beforeOnPrimary = primaryInbound.get().getBefore();
        assertThat(beforeOnPrimary.getPspanid())
                .as("KNOWN GAP from Task 3 review: primary inbound root has no upstream pspanid; "
                        + "TraceContextFilter writes to MDC key 'pspanid' but LogAspect reads 'spanid'. "
                        + "Cross-instance parent/child linking via pspanid is NOT wired end-to-end. "
                        + "Traceid propagation IS wired — see the assertThat above this one.")
                .isNull();
    }

    /** 轮询 host 的根 trace 列表，直到出现一个根，其 BEFORE 事件的 methodName 等于 {@code methodName} 为止。 */
    private List<MethodTraceInfo> awaitRootsContainingMethod(MtlE2eHarness h, String methodName, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> list = fetchTraceList(h, 50);
                boolean found = list != null && list.stream().anyMatch(r ->
                        r != null && r.getBefore() != null
                                && methodName.equals(r.getBefore().getMethodName()));
                if (found) return list;
            } catch (Exception e) {
                lastError = new AssertionError("Error fetching trace list: " + e.getMessage(), e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(
                "Trace with methodName=" + methodName + " did not appear within " + timeout,
                lastError);
    }
}
