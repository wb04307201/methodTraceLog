package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 AlertingService 的冷却逻辑：cooldown-seconds 内同一错误的多次触发只发 1 次 webhook。
 * <p>每个测试用独立 harness（不同端口 + 不同 cooldown 配置），
 * 走 try-with-resources 释放 Spring 上下文。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertingCooldownIT {

    @Test
    void cooldown_suppresses_repeat_alerts_within_window() {
        // cooldown-seconds = 5 via extraProps；webhook URL 指向本 host 的 echo endpoint。
        // 显式 enable alerting 是必须的（默认 application.yml 已 enable，但显式声明更稳）。
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.alerting.enable", "true");
        props.put("method-trace-log.alerting.cooldown-seconds", "5");
        props.put("method-trace-log.alerting.threshold.error-count", "3");
        props.put("method-trace-log.alerting.threshold.window-seconds", "30");
        props.put("method-trace-log.alerting.webhook-url",
                "http://localhost:8095/test/_test/echo-webhook");

        try (MtlE2eHarness host = MtlE2eHarness.primary(8095, props)) {
            host.clearWebhook();

            // First burst: throw 5 times → should fire alert (threshold 3 + 2 more)
            for (int i = 0; i < 5; i++) {
                try {
                    host.http().getForEntity(
                            "http://localhost:8095/test/throw?n=1&message=cooldown-burst1",
                            String.class);
                } catch (Exception ignored) { }
            }

            List<Map<String, Object>> firstBurst = host.awaitWebhook(1, Duration.ofSeconds(5));
            assertThat(firstBurst).as("first burst should fire at least 1 webhook").isNotEmpty();
            int firstCount = firstBurst.size();

            // Within cooldown window (1 second < 5s cooldown): throw 5 more → cooldown should suppress
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < 5; i++) {
                try {
                    host.http().getForEntity(
                            "http://localhost:8095/test/throw?n=1&message=cooldown-burst2",
                            String.class);
                } catch (Exception ignored) { }
            }

            // Give alerts a moment to (not) fire
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // GET current webhook count — should still be firstCount (no new webhook within cooldown)
            @SuppressWarnings("unchecked")
            var currentArr = (List<Map<String, Object>>) host.http().exchange(
                    "http://localhost:8095/test/_test/echo-webhook",
                    HttpMethod.GET, HttpEntity.EMPTY, List.class).getBody();
            assertThat(currentArr.size())
                    .as("webhook count should NOT have grown within cooldown window (was %d, now %d)",
                            firstCount, currentArr != null ? currentArr.size() : 0)
                    .isEqualTo(firstCount);
        }
    }
}
