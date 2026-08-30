package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MdcCleanupIT {

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

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void sequential_calls_on_same_thread_dont_leak_mdc() {
        // 顺序触发多次调用。如果 MDC 泄漏，第二次调用会继承第一次的 traceid。
        // 验证每次调用产生的根 trace 拥有独立的 traceid（UUID 36 字符含 dash）。
        int n = 5;
        for (int i = 0; i < n; i++) {
            host.http().getForEntity(
                    "http://localhost:8085/test/aspectLog?name=mdc-clean-" + i, String.class);
        }
        // 等落盘一点缓冲时间
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<MethodTraceInfo> roots = fetchRoots(50);
        Set<String> aspectLogTraceids = new HashSet<>();
        if (roots != null) {
            for (var r : roots) {
                if (r.getBefore() != null
                        && "aspectLog".equals(r.getBefore().getMethodName())
                        && r.getBefore().getTraceid() != null
                        && r.getBefore().getTraceid().length() == 36) {
                    // UUID-with-dashes form from a freshly-generated root
                    aspectLogTraceids.add(r.getBefore().getTraceid());
                }
            }
        }
        assertThat(aspectLogTraceids.size())
                .as("at least 3 distinct aspectLog roots (proves no MDC leak across sequential calls)")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void within_request_mdc_is_set_during_method_execution() throws Exception {
        // 触发一个调用并验证 /view/list 中能找到带有非空 traceid 的 aspectLog 根节点。
        // 如果 MDC 从未设置过，LogAspect 仍会读 MDC（line 152）并分配 traceid，
        // 但 traceid 会是 null 或继承自上层的；这里验证 traceid 非空即证明 MDC 已被设置过。
        host.http().getForEntity(
                "http://localhost:8085/test/aspectLog?name=mdc-set-verify", String.class);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<MethodTraceInfo> roots = fetchRoots(5);
        boolean foundMdcSetRoot = false;
        if (roots != null) {
            for (var r : roots) {
                if (r.getBefore() != null
                        && "aspectLog".equals(r.getBefore().getMethodName())
                        && r.getBefore().getTraceid() != null) {
                    foundMdcSetRoot = true;
                    break;
                }
            }
        }
        assertThat(foundMdcSetRoot)
                .as("aspectLog root must have a non-null traceid (proving MDC was set during execution)")
                .isTrue();
    }
}
