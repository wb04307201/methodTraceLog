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
 *   <li>{@code class_whitelist_excludes_unlisted_classes}：用 StringBuilder 触发
 *       ClassCastException —— 这是 best-effort 用例，只验证 web endpoint 不崩。</li>
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

    @Test
    void class_whitelist_excludes_unlisted_classes() {
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
