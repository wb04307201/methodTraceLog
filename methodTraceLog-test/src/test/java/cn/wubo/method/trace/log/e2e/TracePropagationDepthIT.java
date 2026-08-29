package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracePropagationDepthIT {

    private MtlE2eHarness host;

    /**
     * Typed fetcher for {@code /methodTraceLog/view/list} — 直接
     * {@code List.class} 会被 Jackson 解成 {@code List<LinkedHashMap>}，
     * 调用 {@code .getBefore()} 时抛 {@code ClassCastException}。这里走
     * {@link ParameterizedTypeReference} 直接落到 {@code List<MethodTraceInfo>}。
     */
    private List<MethodTraceInfo> fetchRoots(int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = host.http().getRestTemplate().exchange(
                "http://localhost:" + host.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET, HttpEntity.EMPTY, typeRef);
        return resp.getBody();
    }

    /**
     * 轮询根 trace 列表，直到出现 methodName="deep" 的根节点。
     */
    private List<MethodTraceInfo> awaitDeepRoots(int minCount, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> list = fetchRoots(Math.max(minCount * 2, 50));
                if (list != null && list.size() >= minCount) {
                    boolean hasDeep = list.stream().anyMatch(r ->
                            r != null && r.getBefore() != null
                                    && "deep".equals(r.getBefore().getMethodName()));
                    if (hasDeep) return list;
                }
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
                "No /test/deep root trace appeared within " + timeout, lastError);
    }

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void deep_nested_tree_has_expected_depth() {
        int targetDepth = 5;
        host.http().getForEntity(
                "http://localhost:8085/test/deep?depth=" + targetDepth, String.class);

        List<MethodTraceInfo> roots = awaitDeepRoots(1, Duration.ofSeconds(5));
        // 找第一个 deep 根（外层调用）
        MethodTraceInfo deepRoot = roots.stream()
                .filter(r -> r.getBefore() != null
                        && "deep".equals(r.getBefore().getMethodName()))
                .findFirst().orElseThrow();
        // 计算树的最大深度
        int maxDepth = maxTreeDepth(deepRoot);
        assertThat(maxDepth)
                .as("trace tree should have at least %d nested levels (controller chain + service.add)", targetDepth + 1)
                .isGreaterThanOrEqualTo(targetDepth);
    }

    @Test
    void deep_chain_shares_single_traceid() {
        int targetDepth = 4;
        host.http().getForEntity(
                "http://localhost:8085/test/deep?depth=" + targetDepth, String.class);

        List<MethodTraceInfo> roots = awaitDeepRoots(1, Duration.ofSeconds(5));
        MethodTraceInfo deepRoot = roots.stream()
                .filter(r -> r.getBefore() != null
                        && "deep".equals(r.getBefore().getMethodName()))
                .findFirst().orElseThrow();
        String rootTraceid = deepRoot.getBefore().getTraceid();
        List<String> allTraceids = new ArrayList<>();
        collectTraceids(deepRoot, allTraceids);
        assertThat(allTraceids)
                .as("all nested calls in the deep chain share the root traceid")
                .allMatch(t -> rootTraceid.equals(t));
    }

    private int maxTreeDepth(MethodTraceInfo node) {
        if (node == null) return 0;
        int childDepth = 0;
        if (node.getChildren() != null) {
            for (var c : node.getChildren()) {
                childDepth = Math.max(childDepth, maxTreeDepth(c));
            }
        }
        return 1 + childDepth;
    }

    private void collectTraceids(MethodTraceInfo node, List<String> sink) {
        if (node == null || node.getBefore() == null) return;
        sink.add(node.getBefore().getTraceid());
        if (node.getChildren() != null) {
            for (var c : node.getChildren()) collectTraceids(c, sink);
        }
    }
}
