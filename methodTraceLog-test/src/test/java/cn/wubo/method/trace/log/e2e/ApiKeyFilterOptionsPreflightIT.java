package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * ApiKeyFilter 对 OPTIONS 预检的端到端验证。
 * <p>
 * 风险 R-26：CORS 预检在 /methodTraceLog/ 命名空间下被 ApiKeyFilter 错误地返回 401。
 * 实际行为（修复后）：OPTIONS 永远放行，无视 api-key 配置。
 * <p>
 * 这里用真实的 Spring Boot 容器 + harness 验证 OPTIONS 请求的最终响应：
 *  <ul>
 *      <li>已配置 api-key 时：OPTIONS 无 Authorization、无 X-Api-Key → 200</li>
 *      <li>GET 不带 key → 401（保持鉴权）</li>
 *  </ul>
 */
class ApiKeyFilterOptionsPreflightIT {

    @Test
    void optionsPreflight_notRejectedWith401() {
        // 风险 R-26 契约：OPTIONS 预检不应被 ApiKeyFilter 用 401 拦截。
        // 实际响应码：路由里没有 OPTIONS handler 时 Spring 会返回 404/405 —— 只要不是 401 即视为通过。
        try (MtlE2eHarness host = MtlE2eHarness.primary(8102,
                Map.of("method-trace-log.security.api-key", "secret"))) {
            var optionsResp = host.http().exchange(
                    "http://localhost:8102/methodTraceLog/view/list",
                    HttpMethod.OPTIONS,
                    new HttpEntity<>(new HttpHeaders()),
                    String.class);

            Assertions.assertNotEquals(HttpStatus.UNAUTHORIZED, optionsResp.getStatusCode(),
                    "OPTIONS 预检不应被 ApiKeyFilter 拦截为 401；got: " + optionsResp.getStatusCode() +
                    " body=" + optionsResp.getBody());
            Assertions.assertNotEquals(HttpStatus.FORBIDDEN, optionsResp.getStatusCode(),
                    "OPTIONS 预检不应被拒绝为 403；got: " + optionsResp.getStatusCode());
        }
    }

    @Test
    void optionsPreflight_doesNotNeedCorsHeadersToPass() {
        // 即便请求不带 Origin / Access-Control-Request-Method 等 CORS 头，
        // ApiKeyFilter 也不应该用 401 拦截 OPTIONS（那是 CORS 框架的事）。
        try (MtlE2eHarness host = MtlE2eHarness.primary(8103,
                Map.of("method-trace-log.security.api-key", "secret"))) {
            var optionsResp = host.http().exchange(
                    "http://localhost:8103/methodTraceLog/view/list",
                    HttpMethod.OPTIONS,
                    new HttpEntity<>(new HttpHeaders()),
                    String.class);

            Assertions.assertNotEquals(HttpStatus.UNAUTHORIZED, optionsResp.getStatusCode(),
                    "OPTIONS 不应被 401 拦截；got: " + optionsResp.getStatusCode());
        }
    }

    @Test
    void getWithoutApiKey_stillReturns401() {
        // 反向断言鉴权本身仍然生效
        try (MtlE2eHarness host = MtlE2eHarness.primary(8104,
                Map.of("method-trace-log.security.api-key", "secret"))) {
            // 用一个不带 X-Api-Key 的独立 TestRestTemplate（不用 harness 自带的）
            org.springframework.boot.test.web.client.TestRestTemplate raw =
                    new org.springframework.boot.test.web.client.TestRestTemplate();

            var resp = raw.getForEntity(
                    "http://localhost:8104/methodTraceLog/view/list",
                    String.class);

            Assertions.assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                    "GET 不带 key 应被 401；got: " + resp.getStatusCode());
        }
    }
}