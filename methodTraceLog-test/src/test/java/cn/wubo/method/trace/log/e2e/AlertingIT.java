package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertingService 端到端测试。
 * <p>
 * 配置（{@code methodTraceLog-test/src/main/resources/application.yml}）：
 * <ul>
 *   <li>{@code method-trace-log.alerting.enable=true}</li>
 *   <li>{@code webhook-url=http://localhost:8085/test/_test/echo-webhook}</li>
 *   <li>{@code threshold.error-count=3}、{@code window-seconds=30}</li>
 *   <li>{@code cooldown-seconds=0}（不抑制抖动）</li>
 *   <li>{@code alerting.classes} 未设置 → 默认空 list → 所有类都告警</li>
 * </ul>
 * <p>
 * 三个用例分别覆盖：
 * <ol>
 *   <li>{@code threshold_3_triggers_webhook_once}：同一 class#method 窗口内 5 次错误
 *       触发 alert，webhook body 携带异常 message。</li>
 *   <li>{@code class_whitelist_endpoint_smoke_test}：用 StringBuilder 触发
 *       ClassCastException —— 这是 <strong>smoke test</strong>，不是真正的 whitelist
 *       断言。原因：默认 {@code alerting.classes} 为空 list → {@code matchesClassFilter}
 *       对任何 class 都返回 true；AOP 切点是 {@code TestController#throwFrom} 而不是
 *       {@code StringBuilder}；因此无法在同一个 {@code AlertingIT} 中既让其他测试告警又
 *       排除本测试。真正的 whitelist 断言需要独立 harness 并通过 {@code extraProps}
 *       配置非空的 {@code alerting.classes} —— 超出当前 {@code AlertingIT} 范围，留待
 *       后续 PR。本测试仅验证 echo-webhook endpoint 能正常服务请求。</li>
 *   <li>{@code renamed_method_name_appears_in_alert}：{@code @AspectLog("renamedThrowing")}
 *       把 methodName 改名为 renamedThrowing，alert body 应只含新名。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertingIT {

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void threshold_3_triggers_webhook_once() {
        host.clearWebhook();
        // Throw 5 times in a row (above threshold=3)
        for (int i = 0; i < 5; i++) {
            try {
                host.http().getForEntity(
                        "http://localhost:8085/test/throw?n=1&message=alert-test",
                        String.class);
            } catch (Exception ignored) { /* expected */ }
        }
        List<Map<String, Object>> webhooks = host.awaitWebhook(1, Duration.ofSeconds(5));
        assertThat(webhooks).isNotEmpty();
        // Body should reference "alert-test" message
        String body = webhooks.get(0).toString();
        assertThat(body).contains("alert-test");
    }

    /**
     * <strong>Smoke test, NOT a real whitelist assertion.</strong>
     * <p>
     * Honest description of what this test verifies:
     * <ul>
     *   <li>The {@code /test/_test/echo-webhook} endpoint serves requests (returns a
     *       non-null body for {@code GET}).</li>
     *   <li>An {@code AFTER_THROW} from {@code TestController#throwFrom} (regardless
     *       of the {@code ?class=} parameter) does not crash the host.</li>
     * </ul>
     * <p>
     * Why this is NOT a real whitelist exclusion assertion:
     * <ul>
     *   <li>The default {@code alerting.classes} is an empty list, so
     *       {@code matchesClassFilter(...)} returns {@code true} for every class —
     *       whitelist exclusion has nothing to filter against.</li>
     *   <li>The AOP joinpoint is {@code TestController#throwFrom}, not the
     *       {@code java.lang.StringBuilder} class passed via {@code ?class=}. The
     *       controller instantiates StringBuilder and tries a
     *       {@code (RuntimeException)} cast, which throws {@code ClassCastException}
     *       — but the joinpoint that fires {@code AFTER_THROW} is still the
     *       controller method, with className {@code cn.wubo.method.trace.log.TestController}.</li>
     *   <li>Tests 1 and 3 also throw from {@code TestController} and rely on the
     *       whitelist being empty (i.e. matching all) for their alerts to fire,
     *       so no single whitelist config can simultaneously let them fire AND
     *       exclude this test.</li>
     * </ul>
     * <p>
     * A real whitelist assertion would require a separate harness started with
     * {@code extraProps} configuring a non-empty {@code alerting.classes} whitelist
     * (e.g. {@code ["cn.wubo.method.trace.log.TestController"]} so this test's
     * class is excluded while another class would still be allowed). That is
     * out of scope for this {@code AlertingIT} and is deferred to a future PR.
     */
    @Test
    void class_whitelist_endpoint_smoke_test() {
        host.clearWebhook();
        // Throw from java.lang.StringBuilder (not in alerting.classes[])
        try {
            host.http().getForEntity(
                    "http://localhost:8085/test/throw-from?class=java.lang.StringBuilder&n=10",
                    String.class);
        } catch (Exception ignored) { /* expected */ }
        // Wait a bit and assert no webhook arrived
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // GET and check size
        @SuppressWarnings("unchecked")
        var received = (List<Map<String, Object>>) host.http().exchange(
                "http://localhost:8085/test/_test/echo-webhook",
                org.springframework.http.HttpMethod.GET,
                org.springframework.http.HttpEntity.EMPTY, List.class).getBody();
        // Should still be at threshold count from previous test or 0; must NOT have grown from StringBuilder throws
        // (best-effort assertion since other tests may run in parallel)
        assertThat(received).isNotNull();
    }

    @Test
    void renamed_method_name_appears_in_alert() {
        host.clearWebhook();
        try {
            host.http().getForEntity(
                    "http://localhost:8085/test/aspectLogRenamedThrow?name=renamed-alert",
                    String.class);
        } catch (Exception ignored) { /* expected */ }
        // Need enough throws to exceed threshold; the renamedThrowing triggers it
        for (int i = 0; i < 4; i++) {
            try {
                host.http().getForEntity(
                        "http://localhost:8085/test/aspectLogRenamedThrow?name=renamed-alert",
                        String.class);
            } catch (Exception ignored) { }
        }
        // Two distinct class#method keys trip alerts here:
        //  - TestController#aspectLogRenamedThrow (the controller's own AFTER_THROW)
        //  - TestComponent#renamedThrowing      (the @AspectLog-annotated internal method)
        // Both reach threshold (3) within the 30s window; we look specifically
        // for the @AspectLog-renamed one and verify its fields directly.
        List<Map<String, Object>> webhooks = host.awaitWebhook(2, Duration.ofSeconds(5));
        assertThat(webhooks).isNotEmpty();
        Map<String, Object> renamedAlert = webhooks.stream()
                .filter(w -> "renamedThrowing".equals(w.get("methodName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected an alert with methodName=renamedThrowing, got: " + webhooks));
        assertThat(renamedAlert.get("className"))
                .as("AlertEvent.className should be the @Component's declaring class")
                .isEqualTo("cn.wubo.method.trace.log.TestComponent");
    }
}
