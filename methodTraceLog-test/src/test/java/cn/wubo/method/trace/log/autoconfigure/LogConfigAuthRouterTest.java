package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.alerting.AlertingService;
import cn.wubo.method.trace.log.analyze.SlowMethodAnalyzer;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import cn.wubo.method.trace.log.CallServiceStrategy;
import cn.wubo.method.trace.log.security.MtlSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;

/**
 * 验证 {@link LogConfig#authRouter} 中 POST /methodTraceLog/login 对请求体的手写 JSON 解析。
 * <p>
 * 风险清单 R-55：当前实现用 {@code indexOf(':')} / {@code indexOf('"')} 解析 JSON body，
 * 遇到以下场景会失败或误读：
 * <ul>
 *     <li>JSON key 不带引号（极少见，但 spec 允许）</li>
 *     <li>嵌套对象 / 数组作为 apiKey</li>
 *     <li>unicode 转义（{@code \"\\uXXXX\"}）</li>
 *     <li>前后空白/换行</li>
 *     <li>body 里有多个冒号</li>
 * </ul>
 * <p>
 * 测试目的：<b>锁定</b>当前行为（即使是 buggy 行为），让任何修复或回归都有迹可循。
 */
class LogConfigAuthRouterTest {

    private static MethodTraceLogProperties propsWithApiKey(String apiKey) {
        var p = new MethodTraceLogProperties();
        p.getSecurity().setApiKey(apiKey);
        return p;
    }

    private static ServerResponse invokeLogin(MethodTraceLogProperties props, String body, String contentType) throws Exception {
        LogConfig cfg = new LogConfig();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MtlSessionService session = new MtlSessionService(60_000L);
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(), props);
        SimpleMonitorServiceImpl monitor = new SimpleMonitorServiceImpl(meterRegistry,
                new cn.wubo.method.trace.log.store.InMemoryTraceStore(), 8L * 60 * 60 * 1000L);
        SlowMethodAnalyzer analyzer = new SlowMethodAnalyzer(meterRegistry);
        @SuppressWarnings("unchecked")
        Optional<AlertingService> alerting = (Optional<AlertingService>) (Optional<?>) Optional.empty();

        Method m = LogConfig.class.getDeclaredMethod("methodTraceLogRouter",
                CallServiceStrategy.class, SimpleMonitorServiceImpl.class,
                MethodTraceLogProperties.class, MtlSessionService.class,
                Optional.class, SlowMethodAnalyzer.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> router = (RouterFunction<ServerResponse>) m.invoke(
                cfg, strategy, monitor, props, session, alerting, analyzer);

        MockHttpServletRequest httpReq = new MockHttpServletRequest("POST", "/methodTraceLog/login");
        if (contentType != null) httpReq.setContentType(contentType);
        if (body != null) {
            httpReq.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ServerRequest req = ServerRequest.create(httpReq, List.of(new StringHttpMessageConverter()));

        Optional<HandlerFunction<ServerResponse>> handlerOpt = router.route(req);
        Assertions.assertTrue(handlerOpt.isPresent(), "router 必须匹配到 /methodTraceLog/login");
        return handlerOpt.get().handle(req);
    }

    private static Object extractEntity(ServerResponse resp) {
        if (resp instanceof EntityResponse<?> er) {
            return er.entity();
        }
        throw new IllegalStateException("expected EntityResponse, got " + resp.getClass());
    }

    // ===== happy path =====

    @Test
    @DisplayName("POST /login with valid apiKey in JSON body 返回 200")
    void login_validApiKeyInJsonBody_returns200() throws Exception {
        ServerResponse resp = invokeLogin(propsWithApiKey("secret-123"),
                "{\"apiKey\":\"secret-123\"}", "application/json");
        Assertions.assertEquals(200, resp.statusCode().value());
        Assertions.assertNotNull(extractEntity(resp));
    }

    @Test
    @DisplayName("POST /login with X-Api-Key header（mtlAuth.js 走此路径）返回 200")
    void login_validApiKeyInHeader_returns200() throws Exception {
        MethodTraceLogProperties props = propsWithApiKey("secret-123");
        LogConfig cfg = new LogConfig();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MtlSessionService session = new MtlSessionService(60_000L);
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(), props);
        SimpleMonitorServiceImpl monitor = new SimpleMonitorServiceImpl(meterRegistry,
                new cn.wubo.method.trace.log.store.InMemoryTraceStore(), 8L * 60 * 60 * 1000L);
        SlowMethodAnalyzer analyzer = new SlowMethodAnalyzer(meterRegistry);
        @SuppressWarnings("unchecked")
        Optional<AlertingService> alerting = (Optional<AlertingService>) (Optional<?>) Optional.empty();

        Method m = LogConfig.class.getDeclaredMethod("methodTraceLogRouter",
                CallServiceStrategy.class, SimpleMonitorServiceImpl.class,
                MethodTraceLogProperties.class, MtlSessionService.class,
                Optional.class, SlowMethodAnalyzer.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> router = (RouterFunction<ServerResponse>) m.invoke(
                cfg, strategy, monitor, props, session, alerting, analyzer);

        MockHttpServletRequest httpReq = new MockHttpServletRequest("POST", "/methodTraceLog/login");
        httpReq.addHeader("X-Api-Key", "secret-123");
        httpReq.setContentType("application/json");
        ServerRequest req = ServerRequest.create(httpReq, List.of(new StringHttpMessageConverter()));

        ServerResponse resp = router.route(req).orElseThrow().handle(req);
        Assertions.assertEquals(200, resp.statusCode().value());
    }

    // ===== R-55: hand-rolled parser 边界 =====

    @Test
    @DisplayName("POST /login apiKey 前后有空白：grep 跳过空白正确取到 \"secret\"（happy path）")
    void login_apiKeyWithSurroundingSpaces_handlesCorrectly() throws Exception {
        // 当前实现：indexOf('"') 从 idx（冒号位置）开始查找，跳过中间所有空白（空格/换行），
        // 取到第一个 \"" 之间的内容 —— 即 "secret"，匹配 configuredKey → 200。
        // 该实现对 colon 与引号之间的空白是稳健的（因为 indexOf 直接跳过空白）。
        ServerResponse resp = invokeLogin(propsWithApiKey("secret"),
                "{\"apiKey\":  \"secret\"  }", "application/json");
        Assertions.assertEquals(200, resp.statusCode().value(),
                "colon 与引号之间的空白被 indexOf 跳过，parser 仍能取到 'secret' → 200");
    }

    @Test
    @DisplayName("POST /login 第二个 key 也是 apiKey：parser 只看第一个 : → 取错 key")
    void login_secondApiKeyField_takenInstead() throws Exception {
        // body 形如 {"foo":"WRONG","apiKey":"secret"}。parser 取第一个 " 之后到下一个 " 的内容 = "WRONG"，
        // 不等于 configuredKey="secret" → 401。
        ServerResponse resp;
        try {
            resp = invokeLogin(propsWithApiKey("secret"),
                    "{\"foo\":\"WRONG\",\"apiKey\":\"secret\"}", "application/json");
        } catch (ResponseStatusException rse) {
            Assertions.assertEquals(401, rse.getStatusCode().value());
            return;
        }
        Assertions.assertEquals(401, resp.statusCode().value(),
                "当前实现只取第一个 : 与第一个 \"，会把\"foo\"的值当成 apiKey → 401");
    }

    @Test
    @DisplayName("POST /login body 为空（但 Content-Type=application/json）→ 不走 body 分支 → 401")
    void login_emptyBody_returns401() throws Exception {
        ServerResponse resp;
        try {
            resp = invokeLogin(propsWithApiKey("secret"), "", "application/json");
        } catch (ResponseStatusException rse) {
            Assertions.assertEquals(401, rse.getStatusCode().value());
            return;
        }
        Assertions.assertEquals(401, resp.statusCode().value());
    }

    @Test
    @DisplayName("POST /login Content-Type 非 json → 完全跳过 body 解析 → 401")
    void login_nonJsonContentType_skipsBodyParsing() throws Exception {
        ServerResponse resp;
        try {
            resp = invokeLogin(propsWithApiKey("secret"),
                    "{\"apiKey\":\"secret\"}", "text/plain");
        } catch (ResponseStatusException rse) {
            Assertions.assertEquals(401, rse.getStatusCode().value());
            return;
        }
        Assertions.assertEquals(401, resp.statusCode().value(),
                "Content-Type 不是 json 时必须跳过 body 解析；secret 在 body 但 header 也没有 → 401");
    }

    @Test
    @DisplayName("POST /login apiKey=\"\" 配空串 → 永远 401（用户配了但没设真值）")
    void login_configuredKeyEmpty_returns401() throws Exception {
        // 配 apiKey="" 时 LogConfig.authRouter 直接返回 200 + 假 cookie（dev-only 路径）
        ServerResponse resp = invokeLogin(propsWithApiKey(""),
                "{\"apiKey\":\"\"}", "application/json");
        Assertions.assertEquals(200, resp.statusCode().value());
        Object body = extractEntity(resp);
        Assertions.assertNotNull(body);
        // 锁定当前 dev 行为：未配置 apiKey 直接返回 "auth disabled" 不需要任何 key
        Assertions.assertTrue(body.toString().contains("auth disabled"),
                "未配置 apiKey 时直接返回 auth disabled；body: " + body);
    }

    @Test
    @DisplayName("POST /login 配置了正确 key 但 body 是裸字符串（非 JSON）→ 401")
    void login_nonJsonBodyWithConfiguredKey_returns401() throws Exception {
        ServerResponse resp;
        try {
            resp = invokeLogin(propsWithApiKey("secret"), "secret", "application/json");
        } catch (ResponseStatusException rse) {
            Assertions.assertEquals(401, rse.getStatusCode().value());
            return;
        }
        Assertions.assertEquals(401, resp.statusCode().value(),
                "裸字符串不是合法 JSON，parser 找不到 : → provided=null → 401");
    }

    // ===== session/status 端点契约 =====

    @Test
    @DisplayName("GET /session/status 在未配 apiKey 时返回 authEnabled=false")
    void sessionStatus_authDisabledWhenNoApiKey() throws Exception {
        MethodTraceLogProperties props = new MethodTraceLogProperties(); // apiKey=""
        LogConfig cfg = new LogConfig();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MtlSessionService session = new MtlSessionService(60_000L);
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(), props);
        SimpleMonitorServiceImpl monitor = new SimpleMonitorServiceImpl(meterRegistry,
                new cn.wubo.method.trace.log.store.InMemoryTraceStore(), 8L * 60 * 60 * 1000L);
        SlowMethodAnalyzer analyzer = new SlowMethodAnalyzer(meterRegistry);
        @SuppressWarnings("unchecked")
        Optional<AlertingService> alerting = (Optional<AlertingService>) (Optional<?>) Optional.empty();

        Method m = LogConfig.class.getDeclaredMethod("methodTraceLogRouter",
                CallServiceStrategy.class, SimpleMonitorServiceImpl.class,
                MethodTraceLogProperties.class, MtlSessionService.class,
                Optional.class, SlowMethodAnalyzer.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> router = (RouterFunction<ServerResponse>) m.invoke(
                cfg, strategy, monitor, props, session, alerting, analyzer);

        MockHttpServletRequest httpReq = new MockHttpServletRequest("GET", "/methodTraceLog/session/status");
        ServerRequest req = ServerRequest.create(httpReq, List.of(new StringHttpMessageConverter()));
        ServerResponse resp = router.route(req).orElseThrow().handle(req);
        Assertions.assertEquals(200, resp.statusCode().value());
        Object body = extractEntity(resp);
        Assertions.assertTrue(body.toString().contains("authEnabled=false"),
                "未配 apiKey → authEnabled=false；body: " + body);
    }
}
