package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.security.MtlSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiKeyFilter 的直接单元测试（不启动 Spring 上下文）。
 * <p>
 * 通过 {@link org.springframework.mock.web.MockHttpServletRequest} +
 * {@link org.springframework.mock.web.MockFilterChain} 直接驱动 filter。
 * <p>
 * 覆盖：
 *  - 关闭鉴权（空 api-key）：所有请求放行
 *  - 缺失 / 错误 / 正确的 X-Api-Key
 *  - panel 路径白名单
 *  - 非 /methodTraceLog/ 路径：filter 直接跳过（shouldNotFilter）
 *  - OPTIONS 预检放行
 *  - MTRACE_SESSION cookie 鉴权
 */
class ApiKeyFilterTest {

    private MethodTraceLogProperties properties;
    private MtlSessionService sessionService;

    @BeforeEach
    void setUp() {
        properties = new MethodTraceLogProperties();
        // security 子对象默认非 null（构造时 new 了 SecurityProperties），
        // apiKey 默认空字符串 = "鉴权关闭"。
        sessionService = new MtlSessionService(60_000L);
    }

    private ApiKeyFilter newFilter() {
        return new ApiKeyFilter(properties, sessionService);
    }

    private static void assertChainInvoked(MockFilterChain chain) {
        // MockFilterChain.getRequest() 会在 doFilter() 被调用后填充回请求；
        // 这里用 getRequest() != null 间接证明 chain.doFilter() 真的跑过。
        // 另一种验证是抓不到 401 状态码。
        assertNotNull(chain.getRequest(), "FilterChain.doFilter should have been invoked");
    }

    @Test
    @DisplayName("关闭鉴权（api-key 为空）：filter 始终放行")
    void disabled_when_no_api_key() throws Exception {
        properties.getSecurity().setApiKey(""); // 默认就是空

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/view/list");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus(), "auth disabled → no 401");
        assertChainInvoked(chain);
    }

    @Test
    @DisplayName("配置了 api-key 但请求没有 X-Api-Key：返回 401")
    void rejects_missing_X_Api_Key() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/view/list");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("unauthorized"));
        // MockFilterChain 应当从未被触发；request 字段应保持 null
        assertEquals(null, chain.getRequest(), "chain should not have been invoked on 401");
    }

    @Test
    @DisplayName("X-Api-Key 值错误：返回 401")
    void rejects_wrong_X_Api_Key() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/view/list");
        req.addHeader(ApiKeyFilter.HEADER, "wrong");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertEquals(null, chain.getRequest());
    }

    @Test
    @DisplayName("X-Api-Key 值正确：通过")
    void accepts_correct_X_Api_Key() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/view/list");
        req.addHeader(ApiKeyFilter.HEADER, "secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertChainInvoked(chain);
    }

    @Test
    @DisplayName("panel 路径在白名单内：无 X-Api-Key 也能通过")
    void panel_path_whitelisted() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/panel");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertChainInvoked(chain);
    }

    @Test
    @DisplayName("非 /methodTraceLog/ 路径：filter shouldNotFilter 直接放行，不查 api-key")
    void non_methodTraceLog_paths_skip_filter() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/other");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertChainInvoked(chain);
    }

    @Test
    @DisplayName("OPTIONS 预检：无视 api-key 配置直接放行")
    void OPTIONS_preflight_allowed() throws Exception {
        properties.getSecurity().setApiKey("secret");

        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/methodTraceLog/view/list");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertChainInvoked(chain);
    }

    @Test
    @DisplayName("有效 MTRACE_SESSION cookie：无需 X-Api-Key 也能通过")
    void cookie_auth_also_accepted() throws Exception {
        properties.getSecurity().setApiKey("secret");
        String sid = sessionService.create();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/methodTraceLog/view/list");
        req.setCookies(new Cookie(MtlSessionService.COOKIE_NAME, sid));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertChainInvoked(chain);
    }
}
