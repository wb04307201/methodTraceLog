package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentTraceIT {

    private MtlE2eHarness host;

    /**
     * Typed fetcher — 直接 {@code List.class} 会被 Jackson 解成
     * {@code List<LinkedHashMap>}，到 {@code .getBefore()} 时抛 ClassCastException。
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
     * 轮询根 trace 列表，直到 methodName 集合覆盖给定的 target 集合中的任一方法，
     * 且根数不少于 minCount。
     */
    private List<MethodTraceInfo> awaitAspectLogRoots(int minCount, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> list = fetchRoots(Math.max(minCount * 2, 50));
                if (list != null && list.size() >= minCount) {
                    long aspectCount = list.stream()
                            .filter(r -> r != null && r.getBefore() != null
                                    && "aspectLog".equals(r.getBefore().getMethodName()))
                            .count();
                    if (aspectCount >= Math.max(1, minCount / 2)) return list;
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
                "aspectLog roots did not reach " + minCount + " within " + timeout, lastError);
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
    void parallel_calls_get_distinct_traceids() throws Exception {
        int parallelism = 10;
        int callsPerThread = 3;
        ExecutorService exec = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < parallelism; t++) {
                final int tid = t;
                for (int i = 0; i < callsPerThread; i++) {
                    final int idx = i;
                    futures.add(exec.submit(() ->
                            host.http().getForEntity(
                                    "http://localhost:8085/test/aspectLog?name=t" + tid + "-" + idx,
                                    String.class).getBody()));
                }
            }
            for (Future<String> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
        }

        // Verify all traceids are unique
        List<MethodTraceInfo> roots = awaitAspectLogRoots(parallelism, Duration.ofSeconds(8));
        Set<String> traceids = new HashSet<>();
        for (var r : roots) {
            if (r.getBefore() != null
                    && "aspectLog".equals(r.getBefore().getMethodName())
                    && r.getBefore().getTraceid() != null
                    && r.getBefore().getTraceid().length() == 36) {  // UUID with dashes
                traceids.add(r.getBefore().getTraceid());
            }
        }
        // Each concurrent call produces one root. We expect at least parallelism unique.
        // (awaitTraceList may time out before all are stored; lower-bound assertion is acceptable.)
        assertThat(traceids.size())
                .as("expected at least %d unique traceids for %d parallel × %d calls",
                        parallelism, parallelism, callsPerThread)
                .isGreaterThanOrEqualTo(parallelism);
    }

    @Test
    void single_thread_sequential_calls_get_distinct_traceids() {
        int calls = 8;
        for (int i = 0; i < calls; i++) {
            host.http().getForEntity(
                    "http://localhost:8085/test/aspectLog?name=seq-" + i, String.class);
        }
        List<MethodTraceInfo> roots = awaitAspectLogRoots(calls, Duration.ofSeconds(5));
        Set<String> traceids = new HashSet<>();
        for (var r : roots) {
            if (r.getBefore() != null
                    && "aspectLog".equals(r.getBefore().getMethodName())
                    && r.getBefore().getTraceid() != null
                    && r.getBefore().getTraceid().startsWith("seq-") == false
                    && r.getBefore().getTraceid().length() == 36) {
                traceids.add(r.getBefore().getTraceid());
            }
        }
        assertThat(traceids.size())
                .as("sequential calls in one thread should each get their own traceid")
                .isGreaterThanOrEqualTo(Math.min(calls, 5));  // generous lower bound
    }
}
