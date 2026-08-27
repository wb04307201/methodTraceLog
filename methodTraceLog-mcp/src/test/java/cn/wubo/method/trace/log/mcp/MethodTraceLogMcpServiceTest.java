package cn.wubo.method.trace.log.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务的工具方法单元测试：用内嵌 JDK HttpServer 接收请求，验证 URL 组装 + 参数 clamp + 主机查找。
 * 不依赖 Spring 上下文，启动快。
 */
class MethodTraceLogMcpServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        lastMethod.set(null);
        lastPath.set(null);
        lastBody.set(null);
        lastApiKey.set(null);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        lastMethod.set(ex.getRequestMethod());
        lastPath.set(ex.getRequestURI().getPath());
        var q = ex.getRequestURI().getQuery();
        lastPath.set(lastPath.get() + (q == null ? "" : "?" + q));
        lastApiKey.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
        byte[] body = ex.getRequestBody().readAllBytes();
        lastBody.set(body.length == 0 ? "" : new String(body));
        ex.sendResponseHeaders(200, 0);
        ex.getResponseBody().write("ok".getBytes());
        ex.getResponseBody().close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private MethodTraceLogMcpService newService(String hostName, String apiKey) {
        MethodTraceLogMcpProperties.HostInfo h = new MethodTraceLogMcpProperties.HostInfo();
        h.setName(hostName);
        h.setUrl(baseUrl());
        h.setApiKey(apiKey);
        List<MethodTraceLogMcpProperties.HostInfo> hosts = new ArrayList<>();
        hosts.add(h);
        return new MethodTraceLogMcpService(hosts, RestClient.create());
    }

    // ============ getAlerts ============

    @Test
    void getAlerts_uses_default_limit_50_when_null() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        String result = svc.getAlerts("local-dev", null);
        assertEquals("ok", result);
        assertEquals("GET", lastMethod.get());
        assertEquals("/methodTraceLog/view/alerts?limit=50", lastPath.get());
    }

    @Test
    void getAlerts_clamps_above_500() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getAlerts("local-dev", 99999);
        assertTrue(lastPath.get().endsWith("limit=500"));
    }

    @Test
    void getAlerts_clamps_below_1() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getAlerts("local-dev", -10);
        assertTrue(lastPath.get().endsWith("limit=1"));
    }

    @Test
    void getAlerts_unknown_host_returns_chinese_message() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        String result = svc.getAlerts("unknown-host", 10);
        assertEquals("主机不存在", result);
        assertEquals(null, lastPath.get());
    }

    // ============ getSlowMethods ============

    @Test
    void getSlowMethods_uses_default_window_and_topN() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        String result = svc.getSlowMethods("local-dev", null, null);
        assertEquals("ok", result);
        assertEquals("/methodTraceLog/view/slowMethods?windowMinutes=5&topN=10", lastPath.get());
    }

    @Test
    void getSlowMethods_clamps_windowMinutes_to_1_60() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getSlowMethods("local-dev", 99999, 5);
        assertTrue(lastPath.get().contains("windowMinutes=60"));

        svc.getSlowMethods("local-dev", 0, 5);
        assertTrue(lastPath.get().contains("windowMinutes=1"));
    }

    @Test
    void getSlowMethods_clamps_topN_to_1_100() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getSlowMethods("local-dev", 5, 99999);
        assertTrue(lastPath.get().contains("topN=100"));

        svc.getSlowMethods("local-dev", 5, 0);
        assertTrue(lastPath.get().contains("topN=1"));
    }

    // ============ getMethodTraceList (existed, exercise query assembly) ============

    @Test
    void getMethodTraceList_no_filters_emits_only_className_path() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getMethodTraceList("local-dev", null, null, null, null);
        // 没有参数时 path 仍以 '?' 结尾 + 紧跟空 body
        assertTrue(lastPath.get().startsWith("/methodTraceLog/view/list"));
    }

    @Test
    void getMethodTraceList_filters_url_encoded() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getMethodTraceList("local-dev", "cn.wubo.X", "do it", true, 50);
        String p = lastPath.get();
        assertTrue(p.contains("className=cn.wubo.X"));
        assertTrue(p.contains("methodName=do+it"));  // 空格 → +
        assertTrue(p.contains("onlyErrors=true"));
        assertTrue(p.contains("limit=50"));
    }

    @Test
    void getMethodTraceList_clamps_limit_to_1_2000() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getMethodTraceList("local-dev", null, null, null, 99999);
        assertTrue(lastPath.get().contains("limit=2000"));

        svc.getMethodTraceList("local-dev", null, null, null, -5);
        assertTrue(lastPath.get().contains("limit=1"));
    }

    // ============ api-key forwarding ============

    @Test
    void api_key_is_forwarded_when_configured() {
        MethodTraceLogMcpService svc = newService("local-dev", "secret-xyz");
        svc.getAlerts("local-dev", 10);
        assertEquals("secret-xyz", lastApiKey.get());
    }

    @Test
    void empty_api_key_omits_header() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getAlerts("local-dev", 10);
        assertEquals(null, lastApiKey.get());
    }

    // ============ ping ============

    @Test
    void ping_hits_actuator_endpoint() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.ping("local-dev");
        assertEquals("/actuator", lastPath.get());
        assertEquals("GET", lastMethod.get());
    }
}