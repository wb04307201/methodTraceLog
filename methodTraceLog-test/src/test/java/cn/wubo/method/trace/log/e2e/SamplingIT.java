package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sampling 端到端测试 — 验证 {@code HeadBasedSampler} 在 0 / 1 / 越界三种边界条件下的行为。
 *
 * <p>每个测试方法用 {@code try-with-resources} 启动独立的
 * {@link MtlE2eHarness}（不同端口），通过 {@code extraProps} 注入
 * {@code method-trace-log.log.sample-rate}。
 *
 * <p>关键路径：
 * <ul>
 *   <li>{@code /test/sampled} 端点是 TestController 的根方法，循环调用
 *       {@code testService.add(i, i+1)} 作为子调用。</li>
 *   <li>{@code LogAspect.around} 对每个拦截的根调用执行
 *       {@code sampler.shouldStartRoot()}，子调用继承父决定
 *       （MDC key {@code mtlSampled}）。</li>
 *   <li>当根调用未采样时，整个调用链不被记录 — 没有 {@code traceid} /
 *       {@code spanid}，也没有 BEFORE / AFTER_* 事件，{@code /view/list}
 *       不出现对应根节点。</li>
 *   <li>越界值（{@code 1.5}）由 {@code LogConfig.mtlSampler()} 的
 *       {@code Math.max(0.0, Math.min(1.0, rate))} 在启动期夹到 1.0，
 *       应用启动不抛异常。</li>
 * </ul>
 *
 * <p><b>断言要点（per parent-task notes）：</b>{@code /view/list} 只返回根 trace
 * （{@code getMethodTraceInfos}），子调用 ({@code add}) 永远不出现在根列表里。
 * brief 的 {@code sample_rate_zero_blocks_all_traces} 断言基于 {@code methodName == "add"}
 * （子调用），即便没采样也会通过（错误的理由）。本测试改查根方法名
 * {@code "sampled"}。</p>
 *
 * <p><b>响应反序列化：</b>直接 {@code List.class} 会被 Jackson 解成
 * {@code List<LinkedHashMap>}（per Ruling 4），调用 {@code .getBefore()} 会抛
 * {@code NoSuchMethodError}。本测试走 {@link ParameterizedTypeReference} 拿到
 * 真正的 {@code List<MethodTraceInfo>}。</p>
 */
class SamplingIT {

    /**
     * Typed fetcher for {@code /methodTraceLog/view/list}。直接 {@code List.class}
     * 会被 Jackson 解成 {@code List<LinkedHashMap>}（per Ruling 4），调用
     * {@code .getBefore()} 时抛 {@code NoSuchMethodError}。这里走
     * {@link ParameterizedTypeReference} 直接落到
     * {@code List<MethodTraceInfo>}。
     */
    private List<MethodTraceInfo> fetchRoots(MtlE2eHarness host, int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = host.http().getRestTemplate().exchange(
                "http://localhost:" + host.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET, HttpEntity.EMPTY, typeRef);
        return resp.getBody();
    }

    /**
     * 轮询 host 的根 trace 列表，直到列表大小达到 {@code minCount}。
     * 拿到后立即返回；如果在 {@code timeout} 内一直达不到，抛 AssertionError。
     */
    private List<MethodTraceInfo> awaitRootsAtLeast(MtlE2eHarness host, int minCount, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> list = fetchRoots(host, Math.max(minCount * 2, 50));
                if (list != null && list.size() >= minCount) return list;
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
                "Trace list did not reach " + minCount + " within " + timeout,
                lastError);
    }

    /**
     * sample-rate=0 → 全部根调用被阻断（{@code shouldStartRoot()} 永远 false），
     * {@code /view/list} 不应出现任何 {@code methodName == "sampled"} 的根节点。
     *
     * <p>50 次 HTTP 调用 → 50 个 root call（{@code /test/sampled} 是 TestController
     * 的端点）→ 每个都被 sampling 阻断 → 全部 trace 不被记录。每个 root 内部还会
     * 触发一次 {@code testService.add(...)} 子调用，但子调用继承父决定同样被阻断。</p>
     *
     * <p>strengthen from brief：brief 查的是子调用 {@code methodName == "add"}，
     * 但 {@code /view/list} 永远不返回子调用 — 断言即使没采样也通过（错误理由）。
     * 这里改查根方法名 {@code "sampled"}。</p>
     */
    @Test
    void sample_rate_zero_blocks_all_traces() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.sample-rate", "0.0");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8090, props)) {
            for (int i = 0; i < 50; i++) {
                try {
                    host.http().getForEntity(
                            "http://localhost:8090/test/sampled?iterations=1", String.class);
                } catch (Exception ignored) {
                    /* root call may surface any wrapping — sampling shouldn't affect HTTP success */
                }
            }
            // 给落盘一点缓冲时间，避免 race。
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            List<MethodTraceInfo> roots = fetchRoots(host, 100);
            long sampledCount = roots == null ? 0 : roots.stream()
                    .filter(r -> r != null && r.getBefore() != null
                            && "sampled".equals(r.getBefore().getMethodName()))
                    .count();
            assertThat(sampledCount)
                    .as("with sample-rate=0, no /test/sampled root should be captured "
                            + "(LogAspect short-circuits entire call tree when shouldStartRoot() returns false; "
                            + "the 50 inbound calls should all be invisible to /view/list)")
                    .isZero();
        }
    }

    /**
     * sample-rate=1.0 → 全部根调用被采样（{@code shouldStartRoot()} 永远 true），
     * {@code /view/list} 应出现 ≥20 条 {@code methodName == "sampled"} 的根节点
     * （每个 HTTP 请求一个根）。
     *
     * <p>strengthen from brief：brief 只断言 {@code >=1}。这里拉到 {@code >=20}，
     * 真正钉住"全部采样"的语义 — 任何对 {@code shouldStartRoot()} 的回归
     * （例如被改成随机抽样）都会让本测试失败。</p>
     */
    @Test
    void sample_rate_one_captures_all_traces() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.sample-rate", "1.0");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8091, props)) {
            for (int i = 0; i < 20; i++) {
                try {
                    host.http().getForEntity(
                            "http://localhost:8091/test/sampled?iterations=1", String.class);
                } catch (Exception ignored) { /* expected */ }
            }
            // 等至少 20 条根 trace 出现；awaitRootsAtLeast 内部 200 ms 轮询。
            List<MethodTraceInfo> roots = awaitRootsAtLeast(host, 20, Duration.ofSeconds(5));
            long sampledCount = roots.stream()
                    .filter(r -> r != null && r.getBefore() != null
                            && "sampled".equals(r.getBefore().getMethodName()))
                    .count();
            assertThat(sampledCount)
                    .as("with sample-rate=1.0, all 20 /test/sampled calls should be captured "
                            + "(shouldStartRoot() always returns true; the whole call tree — root + "
                            + "child add() calls — is recorded for each request)")
                    .isGreaterThanOrEqualTo(20);
        }
    }

    /**
     * sample-rate=1.5（越界）→ 由 {@code LogConfig.mtlSampler()} 的
     * {@code Math.max(0.0, Math.min(1.0, rate))} clamp 到 1.0；应用启动不抛异常，
     * {@code /actuator/health} 返回 2xx。
     *
     * <p>如果未来该 clamp 被移除，{@code HeadBasedSampler} 构造器会对 {@code 1.5}
     * 抛 {@code IllegalArgumentException}，整个 Spring 上下文启动失败 →
     * 本测试会失败。这是为了锁住 clamp 行为。</p>
     */
    @Test
    void sample_rate_out_of_range_clamps_to_one() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.sample-rate", "1.5");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8092, props)) {
            var resp = host.http().getForEntity(
                    "http://localhost:8092/actuator/health", String.class);
            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("app should start cleanly with out-of-range sample-rate=1.5 "
                            + "(LogConfig.mtlSampler() clamps to 1.0 via Math.max/Math.min; "
                            + "without this clamp HeadBasedSampler would throw and break startup)")
                    .isTrue();
        }
    }
}