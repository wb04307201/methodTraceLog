package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 端到端测试。
 *
 * <p>覆盖两条核心路径:
 * <ol>
 *   <li>{@code preflight_options_returns_cors_headers}: OPTIONS 预检请求
 *       {@code /methodTraceLog/view/callServices} 应在 2xx + 正确的 CORS 响应头
 *       （{@code Access-Control-Allow-Origin} + {@code Access-Control-Allow-Methods}）。</li>
 *   <li>{@code cors_info_endpoint_echoes_origin_header}: GET
 *       {@code /test/cors-info}（非 methodTraceLog 命名空间,绕过 CORS）应能正确
 *       回显 {@code Origin} 请求头 —— 验证 {@code TestController.corsInfo} 与
 *       Harness 的 X-Api-Key 注入未冲突。</li>
 * </ol>
 *
 * <p><b>关于 CORS 配置（per brief 关注点 1 + task 实测发现）:</b>
 * {@code methodTraceLog-test/src/main/resources/application.yml} 必须配置
 * {@code method-trace-log.security.cors.allowed-origins=http://localhost:3000}。
 * 否则 bean 创建的 {@link org.springframework.web.filter.CorsFilter} 拿不到
 * 任何 {@code CorsConfiguration},preflight 落到 Spring MVC 默认处理(返回 403/404)而不是 CORS 头。
 *
 * <p><b>关于 preflight 响应码 200 vs 204（per task 实测,纠正 brief 假设）:</b>
 * brief 假设 Spring 返回 204。实测 Spring Boot 3.5 + Spring 6.1 的
 * {@code DefaultCorsProcessor} 对 preflight 一律返回 200 OK（与 max-age 无关）。
 * 这是 RFC 7231 / fetch spec 允许的两种 preflight 响应之一,不影响功能。
 * 本测试用 {@code is2xxSuccessful()} 兼容 200/204 两种实现。
 *
 * <p><b>关于 OPTIONS 预检无需 X-Api-Key（per brief 关注点 3 + CLAUDE.md）:</b>
 * {@code ApiKeyFilter.shouldNotFilter} 里对 {@code OPTIONS} 显式
 * {@code chain.doFilter(request, response)} —— preflight 直通,401 永远不会出在 preflight 上。
 *
 * <p><b>关于 /test/cors-info（per brief 关注点 2）:</b>
 * 该端点在 {@code TestController} 里存在,返回 {@code "cors:" + req.getHeader("Origin")}。
 * 它不在 {@code /methodTraceLog/**} 下,所以 CORS filter 不匹配,
 * {@code Access-Control-Allow-Origin} 响应头也不会出现在响应里 —— 这就是为什么本测试只
 * 断言 body 内容而不断言响应头。
 *
 * <p><b>关于 test 2 加强（per brief "Strengthen" 段落）:</b>
 * 把 {@code resp.getBody().contains(...)} 收窄为 {@code isEqualTo("cors:http://localhost:3000")}，
 * 把"包含 origin 字符串"提升为"body 完全等于预期格式"。
 *
 * <p><b>关于 port 8085（per brief 关注点 4）:</b>
 * Surefire 给每个 IT 跑独立 JVM，端口冲突不构成问题；和现有 {@code AlertingIT /
 * SlowMethodIT / SamplingIT / ExcludePatternIT / TraceStoreIT / LogFileQueryIT /
 * LogFileMonitorIT / DecompileIT / SessionAuthIT / TracePropagationIT /
 * OtelPropagationIT} 全部共用 8085。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorsIT {

    private static final int PORT = 8085;

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(PORT, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    /**
     * 验证 CORS preflight：OPTIONS /methodTraceLog/view/callServices
     * 必须返回 2xx + Access-Control-Allow-Origin=http://localhost:3000。
     */
    @Test
    void preflight_options_returns_cors_headers() {
        HttpHeaders h = new HttpHeaders();
        h.add("Origin", "http://localhost:3000");
        h.add("Access-Control-Request-Method", "GET");
        h.add("Access-Control-Request-Headers", "X-Api-Key");
        var entity = new HttpEntity<>(h);
        var resp = host.http().exchange(
                "http://localhost:" + PORT + "/methodTraceLog/view/callServices",
                HttpMethod.OPTIONS, entity, Void.class);

        // Spring 6.x 的 DefaultCorsProcessor 对 preflight 在 max-age<=0 时返回 204，
        // max-age>0 时返回 200 —— 两种都是 RFC 7231 允许的 preflight 响应。
        // 实测 Spring Boot 3.5 + Spring 6.1 默认走 200,所以这里放宽到 2xx。
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("OPTIONS preflight should return 2xx; got %s", resp.getStatusCode())
                .isTrue();

        assertThat(resp.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .as("Access-Control-Allow-Origin must echo the requesting origin")
                .isEqualTo("http://localhost:3000");

        // Strengthen (per brief): 验证 CORS 响应头确实声明了允许的方法 —— 锁住
        // CorsFilterConfig 把 allowedMethods 列表 setAllowMethods(...) 的契约。
        assertThat(resp.getHeaders().getFirst("Access-Control-Allow-Methods"))
                .as("Access-Control-Allow-Methods must list GET")
                .contains("GET");
    }

    /**
     * 验证 TestController#corsInfo 在 harness X-Api-Key 下能正确回显 Origin。
     * 加强：body 必须严格等于 "cors:http://localhost:3000"。
     */
    @Test
    void cors_info_endpoint_echoes_origin_header() {
        HttpHeaders h = new HttpHeaders();
        h.add("Origin", "http://localhost:3000");
        h.add("X-Api-Key", "change-me-in-production");
        var resp = host.http().exchange(
                "http://localhost:" + PORT + "/test/cors-info",
                HttpMethod.GET, new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /test/cors-info should return 2xx; got %s body=%s",
                        resp.getStatusCode(), resp.getBody())
                .isTrue();

        // Strengthen (per brief): 严格等于预期拼接字符串,不只是"包含"。
        assertThat(resp.getBody())
                .as("/test/cors-info must echo Origin as 'cors:<origin>'")
                .isEqualTo("cors:http://localhost:3000");
    }
}
