package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.security.MtlSessionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Session-based auth 端到端测试。
 *
 * <p>覆盖两条核心路径:
 * <ol>
 *   <li>{@code login_then_session_status_returns_ok}: POST /login with valid X-Api-Key
 *       header → 200 + Set-Cookie; 之后带 cookie GET /session/status → 200 且
 *       {@code sessionValid=true}。</li>
 *   <li>{@code without_auth_returns_401_on_protected_endpoint}: harness 加了 X-Api-Key
 *       走 /view/callServices → 200; 用裸 {@link RestTemplate}（无 X-Api-Key）走同一
 *       路径 → 401。</li>
 * </ol>
 *
 * <p><b>关于 X-Api-Key 放在 header 而不是 body(per brief 关注点 1):</b>
 * 看 {@code mtlAuth.js} 的真实路径 —— 它把 key 放在 {@code X-Api-Key} 请求头里;而
 * {@code LogConfig.authRouter} 里 login handler 优先读 header 再回退到 body 的简单
 * JSON grep。所以这里把 key 放 header,跟 mtlAuth.js 行为对齐,也避开了 body 解析
 * 对 Content-Length 的依赖(裸 RestTemplate 在 chunked 编码下 read() 可能只拿到 1 字节)。
 *
 * <p><b>关于 Set-Cookie 的双重来源(per brief 关注点 2):</b>
 * {@code LogConfig.authRouter} 里 login handler 同时用
 * {@code ServerResponse.cookie(new Cookie(...))} 和显式
 * {@code .header("Set-Cookie", "MTRACE_SESSION=...; ...")} 设置 cookie,
 * 所以响应里会有两条 Set-Cookie。{@code responseHeaders.get(HttpHeaders.SET_COOKIE)}
 * 返回 {@code List<String>},两条都有,任取其一即可解析出 sessionId。
 *
 * <p><b>关于 /session/status 的白名单(per brief 关注点 1):</b>
 * 该端点在 {@link cn.wubo.method.trace.log.autoconfigure.ApiKeyFilter} 的
 * {@code PUBLIC_PATHS} 里(同 panel/login/logout),因此即使没带 X-Api-Key 也直接
 * 放行;但要看 cookie 是否有效 → {@code sessionValid} 字段为 true。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionAuthIT {

    private static final int PORT = 8085;
    private static final String API_KEY = "change-me-in-production";

    private MtlE2eHarness host;
    private RestTemplate cookieClient;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(PORT, Map.of());
        // 独立的 RestTemplate,不挂 X-Api-Key interceptor —— 用于走"无 cookie 也无 apiKey"的路径
        cookieClient = new RestTemplate();
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    /**
     * 验证浏览器登录流程：
     * POST /login 拿到 Set-Cookie;之后 GET /session/status 用 cookie 应答 200 + sessionValid=true。
     */
    @Test
    void login_then_session_status_returns_ok() {
        // 1. POST /login —— 走 X-Api-Key header 路径（与 mtlAuth.js 一致）
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.set("X-Api-Key", API_KEY);
        ResponseEntity<Map> loginResp = cookieClient.postForEntity(
                "http://localhost:" + PORT + "/methodTraceLog/login",
                new HttpEntity<>("{}", loginHeaders),
                Map.class);

        assertThat(loginResp.getStatusCode().is2xxSuccessful())
                .as("POST /login with valid X-Api-Key should return 2xx; got %s body=%s",
                        loginResp.getStatusCode(), loginResp.getBody())
                .isTrue();

        // 2. 解析 Set-Cookie。MtlSessionService.COOKIE_NAME = "MTRACE_SESSION"
        String sessionId = extractSessionId(loginResp.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertThat(sessionId)
                .as("login response must carry a MTRACE_SESSION cookie (Set-Cookie header); headers=%s",
                        loginResp.getHeaders())
                .isNotNull()
                .matches("[0-9a-f]{32}");

        // 3. GET /session/status 带 cookie
        HttpHeaders statusHeaders = new HttpHeaders();
        statusHeaders.add(HttpHeaders.COOKIE,
                MtlSessionService.COOKIE_NAME + "=" + sessionId);
        ResponseEntity<Map> statusResp = cookieClient.exchange(
                "http://localhost:" + PORT + "/methodTraceLog/session/status",
                HttpMethod.GET, new HttpEntity<>(statusHeaders), Map.class);

        assertThat(statusResp.getStatusCode().is2xxSuccessful())
                .as("GET /session/status with valid cookie should return 2xx; got %s",
                        statusResp.getStatusCode())
                .isTrue();

        // 4. body 断言：authEnabled=true（apiKey 已配置）, sessionValid=true（cookie 命中）
        Map<String, Object> body = statusResp.getBody();
        assertThat(body)
                .as("/session/status body should not be null")
                .isNotNull();
        assertThat(body.get("authEnabled"))
                .as("/session/status.authEnabled must be true when apiKey is configured")
                .isEqualTo(Boolean.TRUE);
        assertThat(body.get("sessionValid"))
                .as("/session/status.sessionValid must be true with a fresh cookie")
                .isEqualTo(Boolean.TRUE);
    }

    /**
     * 验证 ApiKeyFilter 对受保护端点的强制鉴权：
     * harness 的 X-Api-Key 走通 → 200; 裸 RestTemplate 没头 → 401。
     */
    @Test
    void without_auth_returns_401_on_protected_endpoint() {
        // 1. 有 X-Api-Key(harness 加的)→ 应该 200
        // /view/callServices 返回 List<Map<String, Object>>(CallServiceStrategy.getCallServices),
        // 不能用 Map.class —— 会撞 "JSON array 解析不了成 Map"。
        ResponseEntity<List> withKey = host.http().getForEntity(
                "http://localhost:" + PORT + "/methodTraceLog/view/callServices", List.class);
        assertThat(withKey.getStatusCode().is2xxSuccessful())
                .as("with X-Api-Key harness header, /view/callServices should return 2xx; got %s",
                        withKey.getStatusCode())
                .isTrue();
        assertThat(withKey.getBody())
                .as("/view/callServices body should be a non-empty list")
                .isNotNull()
                .isNotEmpty();

        // 2. 没 X-Api-Key、没 cookie → ApiKeyFilter 写 401 JSON 响应
        try {
            new RestTemplate().getForEntity(
                    "http://localhost:" + PORT + "/methodTraceLog/view/callServices", List.class);
            fail("expected HttpClientErrorException for 401, but request succeeded");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value())
                    .as("bare RestTemplate without X-Api-Key should yield 401; got %s body=%s",
                            e.getStatusCode(), e.getResponseBodyAsString())
                    .isEqualTo(401);
        }
    }

    /**
     * 从 Set-Cookie 列表里挑出 MTRACE_SESSION 的原始值（不包含 Path/Max-Age 等属性）。
     * login handler 同时写两条 Set-Cookie，一条来自 {@code ServerResponse.cookie(...)}，
     * 一条来自显式 {@code .header("Set-Cookie", ...)}。任一条都能拿到同一个 sid。
     */
    private static String extractSessionId(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return null;
        for (String header : setCookieHeaders) {
            if (header == null) continue;
            int eq = header.indexOf('=');
            if (eq <= 0) continue;
            String name = header.substring(0, eq).trim();
            if (!MtlSessionService.COOKIE_NAME.equals(name)) continue;
            String valueAndAttrs = header.substring(eq + 1);
            int semi = valueAndAttrs.indexOf(';');
            return semi < 0 ? valueAndAttrs.trim() : valueAndAttrs.substring(0, semi).trim();
        }
        return null;
    }
}
