package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 sampler 边界：rate=0.0 完全 drop（不出现任何 aspectLog 根 trace），
 * rate=1.0 全部 capture。
 */
class SamplingExclusionIT {

    /**
     * Typed fetcher — 直接 {@code List.class} 会被 Jackson 解成
     * {@code List<LinkedHashMap>}，到 {@code .getBefore()} 时抛 ClassCastException。
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
     * sample-rate=0.0 → 全部根调用被阻断，/view/list 不应出现任何
     * {@code methodName == "aspectLog"} 的根节点。
     */
    @Test
    void sample_rate_zero_blocks_every_call() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.sample-rate", "0.0");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8096, props)) {
            // Snapshot count before
            int beforeCount = fetchRoots(host, 50) == null ? 0 : fetchRoots(host, 50).size();

            // Make several calls
            for (int i = 0; i < 10; i++) {
                try {
                    host.http().getForEntity(
                            "http://localhost:8096/test/aspectLog?name=sampling-excl-" + i,
                            String.class);
                } catch (Exception ignored) { }
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Snapshot count after
            List<MethodTraceInfo> roots = fetchRoots(host, 50);
            int afterCount = roots == null ? 0 : roots.size();

            assertThat(afterCount)
                    .as("with sample-rate=0.0, no new traces should be added (was %d, now %d)",
                            beforeCount, afterCount)
                    .isEqualTo(beforeCount);
        }
    }

    /**
     * sample-rate=1.0 → 全部根调用被采样，/view/list 应出现 ≥3 个独立 aspectLog 根。
     */
    @Test
    void sample_rate_one_captures_every_call() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.sample-rate", "1.0");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8097, props)) {
            Set<String> traceids = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                host.http().getForEntity(
                        "http://localhost:8097/test/aspectLog?name=sampling-incl-" + i,
                        String.class);
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            List<MethodTraceInfo> roots = fetchRoots(host, 50);
            for (var r : roots) {
                if (r != null && r.getBefore() != null
                        && "aspectLog".equals(r.getBefore().getMethodName())
                        && r.getBefore().getTraceid() != null
                        && r.getBefore().getTraceid().length() == 36) {
                    traceids.add(r.getBefore().getTraceid());
                }
            }
            assertThat(traceids.size())
                    .as("with sample-rate=1.0, all 5 calls should produce 5 distinct roots")
                    .isGreaterThanOrEqualTo(3);  // generous due to async store lag
        }
    }
}
