package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code SlowMethodAnalyzer} + {@code /view/slowMethods} 端点：
 * 调用真实 {@code /test/slow?sleepMs=1500} 多次（每个请求阻塞 1.5 s），让
 * {@code SimpleMonitorServiceImpl} 把 {@code AFTER_RETURN} 事件注册成
 * {@code Timer.builder("method.execution.time")...}，然后请求
 * {@code GET /methodTraceLog/view/slowMethods?windowMinutes=5&topN=10}，
 * 期望返回的 {@code List<SlowMethodStats>} 至少包含一条
 * {@code methodSignature} 字段包含 {@code "slow"}（即
 * {@code TestController#slow(long)} 的 long signature）的项。
 *
 * <p><b>Micrometer 采样时延：</b>Timer 样本是在
 * {@code SimpleMonitorServiceImpl.consumer(...)} 的
 * {@code AFTER_RETURN} 分支里
 * {@code sample.stop(Timer.builder(...).register(meterRegistry))} 完成的，
 * stop 之后 histogram 才会更新；测试用 5 次慢调用填出稳定 histogram 后
 * 等 4 s（比 brief 默认的 2 s 更稳，呼应 Task 3 review 中
 * "Micrometer timing is not instant" 的反馈），再读取 slowMethods。</p>
 *
 * <p><b>响应反序列化：</b>{@code /view/slowMethods} 返回
 * {@code List<SlowMethodStats>}；为了走强类型字段（{@code className} /
 * {@code methodSignature} / {@code p99}）的清晰断言，
 * 这里用 {@link ParameterizedTypeReference}
 * 直接拿到 {@code List<Map<String, Object>>}，避免 raw {@code List.class}
 * 被 Jackson 解成 {@code List<LinkedHashMap>}（per Ruling 4：raw List.class
 * 拿不到 structured fields，只能 toString contains 检查；本测试要
 * contains 检查 methodSignature 字段，必须先 typed 解）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowMethodIT {

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    /**
     * 把 5 次慢调用打到 {@code /test/slow?sleepMs=1500}，让 Micrometer Timer 收到
     * 足够的样本；等 4 s 给 Micrometer 的 histogram 落地时间，然后断言
     * {@code /view/slowMethods} 返回的列表里至少有一个 methodSignature 含
     * {@code "slow"}（即 TestController#slow(long) 的 long signature）。
     *
     * <p>5 次调用 + 1.5 s sleep 总耗时 ~7.5 s，再加上 4 s 等待 ≈ 11.5 s
     * 单测，落在 30 s 默认 JUnit 超时之内。</p>
     */
    @Test
    void slow_endpoint_appears_in_slow_methods_list() {
        // Fire several slow calls so histogram has data
        for (int i = 0; i < 5; i++) {
            host.http().getForEntity(
                    "http://localhost:8085/test/slow?sleepMs=1500", String.class);
        }
        // Wait for Micrometer to register samples — 4 s gives the histogram
        // a stable window (Task 3 review feedback: Micrometer timing is not instant).
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Hit /methodTraceLog/view/slowMethods?windowMinutes=5&topN=10
        ParameterizedTypeReference<List<Map<String, Object>>> typeRef =
                new ParameterizedTypeReference<List<Map<String, Object>>>() {};
        ResponseEntity<List<Map<String, Object>>> resp = host.http().exchange(
                "http://localhost:8085/methodTraceLog/view/slowMethods?windowMinutes=5&topN=10",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                typeRef);
        List<Map<String, Object>> slowMethods = resp.getBody();

        assertThat(slowMethods)
                .as("slowMethods endpoint should return a non-empty list after 5 slow calls")
                .isNotNull()
                .isNotEmpty();

        // Strengthen from brief's "list.toString().contains('slow')" (weak) to:
        // at least one entry has a "methodSignature" field containing "slow".
        boolean foundSlow = slowMethods.stream()
                .anyMatch(m -> {
                    Object sig = m.get("methodSignature");
                    return sig != null && sig.toString().contains("slow");
                });
        assertThat(foundSlow)
                .as("expected at least one entry with methodSignature containing 'slow' "
                        + "(TestController#slow(long)), got: " + slowMethods)
                .isTrue();
    }
}