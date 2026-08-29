package cn.wubo.method.trace.log.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务的工具方法单元测试：用内嵌 JDK HttpServer 接收请求，验证 URL 组装 + 参数 clamp + 主机查找 +
 * 重试 / 错误 / 大小上限 / 启动期校验。
 * <p>每个测试用例用一个全新的内嵌 HttpServer，避免前置用例遗留的副作用影响当前断言。
 */
class MethodTraceLogMcpServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        lastMethod.set(null);
        lastPath.set(null);
        lastBody.set(null);
        lastApiKey.set(null);
        requestCount.set(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
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

    /** Mock server that responds with the given status and JSON body. */
    private com.sun.net.httpserver.HttpServer statusOnlyServer(HttpStatus status, String jsonBody) throws IOException {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            requestCount.incrementAndGet();
            byte[] data = jsonBody.getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status.value(), data.length);
            ex.getResponseBody().write(data);
            ex.getResponseBody().close();
        });
        s.start();
        return s;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private MethodTraceLogMcpService newService(String hostName, String apiKey) {
        return newService(hostName, apiKey, RestClient.create());
    }

    private MethodTraceLogMcpService newService(String hostName, String apiKey, RestClient client) {
        MethodTraceLogMcpProperties.HostInfo h = new MethodTraceLogMcpProperties.HostInfo();
        h.setName(hostName);
        h.setUrl(baseUrl());
        h.setApiKey(apiKey);
        List<MethodTraceLogMcpProperties.HostInfo> hosts = new ArrayList<>();
        hosts.add(h);
        return new MethodTraceLogMcpService(hosts, client);
    }

    private MethodTraceLogMcpProperties.HostInfo hostInfo(String name, String url, String apiKey) {
        MethodTraceLogMcpProperties.HostInfo h = new MethodTraceLogMcpProperties.HostInfo();
        h.setName(name);
        h.setUrl(url);
        h.setApiKey(apiKey == null ? "" : apiKey);
        return h;
    }

    /** Build a small-timeout RestClient to keep timeout tests fast. */
    private RestClient fastTimeoutClient(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    // ============ existing getAlerts / getSlowMethods / api-key / ping tests ============

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
        assertNull(lastPath.get());
    }

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

    @Test
    void getMethodTraceList_no_filters_emits_only_className_path() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getMethodTraceList("local-dev", null, null, null, null);
        assertTrue(lastPath.get().startsWith("/methodTraceLog/view/list"));
    }

    @Test
    void getMethodTraceList_url_encodes_space_as_pct20_not_plus() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.getMethodTraceList("local-dev", "cn.wubo.X", "do it", true, 50);
        String p = lastPath.get();
        assertTrue(p.contains("className=cn.wubo.X"));
        // RFC 3986: space → %20, NOT +
        assertTrue(p.contains("methodName=do%20it"),
                "expected methodName=do%20it in path, got: " + p);
        assertTrue(!p.contains("methodName=do+it"),
                "should not contain form-encoded '+', got: " + p);
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
        assertNull(lastApiKey.get());
    }

    @Test
    void ping_hits_actuator_endpoint() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.ping("local-dev");
        assertEquals("/actuator", lastPath.get());
        assertEquals("GET", lastMethod.get());
    }

    // ============ MCP-R-11 url encoding ============

    @Test
    void urlEncode_space_becomes_pct20() {
        assertEquals("do%20it", MethodTraceLogMcpService.urlEncode("do it"));
        assertEquals("a%2Fb", MethodTraceLogMcpService.urlEncode("a/b"));
        assertEquals("a%26b", MethodTraceLogMcpService.urlEncode("a&b"));
        assertEquals("a%3Db", MethodTraceLogMcpService.urlEncode("a=b"));
        assertEquals("", MethodTraceLogMcpService.urlEncode(null));
    }

    @Test
    void queryLogContent_passes_plus_through_unchanged() {
        // If the user passes "do+it" literally, it should reach the host verbatim — only true
        // spaces are converted to %20. Form-encoded "+" stays "+".
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.queryLogContent("local-dev", "do+it.log", null, null, null, null, null);
        assertTrue(lastPath.get().endsWith("/methodTraceLog/logFile/query"));
        assertTrue(lastBody.get().contains("\"fileName\":\"do+it.log\""),
                "expected literal '+' to be preserved in JSON body, got: " + lastBody.get());
    }

    // ============ MCP-R-02 uncaught exceptions -> structured JSON ============

    @Test
    void safeGet_server_returns_500_returns_hostError_json() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.INTERNAL_SERVER_ERROR, "{\"err\":\"boom\"}");
        try {
            MethodTraceLogMcpProperties.HostInfo h = hostInfo("local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.getAlerts("local-dev", 10);
            // Fast ops are retryable: a 500 counts as a server error which is retryable, so after 3
            // attempts the safeCall returns the structured error.
            assertTrue(result.contains("\"error\":"), "expected JSON error envelope, got: " + result);
            assertTrue(result.contains("HOST_ERROR"), "expected HOST_ERROR in: " + result);
            assertTrue(result.contains("\"host\":\"local-dev\""), "expected host name in: " + result);
            // Retries for retryable op: 3 calls total
            assertEquals(3, requestCount.get(), "expected 3 retry attempts on retryable GET");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void safeGet_connect_refused_returns_hostUnreachable_json() {
        // Use a port nobody is listening on (bind + close to grab a free port).
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        MethodTraceLogMcpProperties.HostInfo h = hostInfo("local-dev", "http://127.0.0.1:" + port, "");
        // Use non-retryable ping? ping is retryable. But hostUnreachable should still surface.
        // Use startMonitor (non-retryable) to avoid the 35s wait for 3 retry attempts.
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
        String result = svc.startMonitor("local-dev", "app.log");
        assertTrue(result.contains("\"error\":"), "expected JSON error envelope, got: " + result);
        assertTrue(result.contains("HOST_UNREACHABLE") || result.contains("CONNECT"),
                "expected HOST_UNREACHABLE in: " + result);
        assertTrue(result.contains("\"host\":\"local-dev\""), "expected host name in: " + result);
    }

    @Test
    void safeGet_404_returns_notFound_json() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.NOT_FOUND, "{\"msg\":\"no\"}");
        try {
            MethodTraceLogMcpProperties.HostInfo h = hostInfo("local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            // getMethodTraceByTraceId is retryable; use it.
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.getMethodTraceByTraceId("local-dev", "abc");
            assertTrue(result.contains("NOT_FOUND"), "expected NOT_FOUND in: " + result);
        } finally {
            s.stop(0);
        }
    }

    // ============ MCP-R-03 size cap ============

    @Test
    void safeGet_oversized_body_returns_responseTooLarge_json() throws Exception {
        // Body size 2 MiB; cap 1 MiB -> must trip the size limit.
        byte[] bigBody = new byte[2 * 1024 * 1024];
        Arrays.fill(bigBody, (byte) 'A');
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            requestCount.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, bigBody.length);
            try {
                ex.getResponseBody().write(bigBody);
            } catch (IOException ignored) {
                // peer may cut the connection after seeing the violation; that's fine.
            } finally {
                ex.getResponseBody().close();
            }
        });
        s.start();
        try {
            // getLogFiles is retryable; configure a 1 MiB cap.
            SizeLimitingClientHttpRequestFactory sizeLimit = new SizeLimitingClientHttpRequestFactory(
                    new JdkClientHttpRequestFactory(), 1024 * 1024L);
            RestClient cappedClient = RestClient.builder().requestFactory(sizeLimit).build();
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), cappedClient);
            String result = svc.getLogFiles("local-dev");
            assertTrue(result.contains("\"error\":"), "expected JSON error envelope, got: " + result);
            assertTrue(result.contains("RESPONSE_TOO_LARGE"), "expected RESPONSE_TOO_LARGE in: " + result);
            assertTrue(result.contains("\"host\":\"local-dev\""), "expected host name in: " + result);
        } finally {
            s.stop(0);
        }
    }

    // ============ MCP-R-04 retry ============

    @Test
    void safeGet_retryable_succeeds_after_2_failures() throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            int n = requestCount.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            if (n < 3) {
                ex.sendResponseHeaders(502, 0);
                ex.getResponseBody().close();
            } else {
                byte[] body = "\"recovered\"".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.getResponseBody().close();
            }
        });
        s.start();
        try {
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            // ping is retryable
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.ping("local-dev");
            assertEquals(3, requestCount.get(), "expected exactly 3 attempts (2 fails + 1 success)");
            assertTrue(result.contains("recovered"), "expected success body, got: " + result);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void safeGet_retryable_returns_error_after_all_attempts() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.BAD_GATEWAY, "{\"err\":\"fail\"}");
        try {
            requestCount.set(0);
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.getAlerts("local-dev", 10);
            assertTrue(result.contains("HOST_ERROR"), "expected HOST_ERROR in: " + result);
            assertEquals(3, requestCount.get(), "expected 3 attempts on retryable GET");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void safePost_nonRetryable_does_not_retry() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.INTERNAL_SERVER_ERROR, "{\"err\":\"oops\"}");
        try {
            requestCount.set(0);
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.queryLogContent("local-dev", "app.log", null, null, null, null, null);
            assertTrue(result.contains("HOST_ERROR"), "expected HOST_ERROR in: " + result);
            assertEquals(1, requestCount.get(), "POST / queryLogContent should NOT retry");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void safeGet_setCallServiceEnable_does_not_retry() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.INTERNAL_SERVER_ERROR, "{\"err\":\"oops\"}");
        try {
            requestCount.set(0);
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            String result = svc.setCallServiceEnable("local-dev", "svc", true);
            assertTrue(result.contains("HOST_ERROR"), "expected HOST_ERROR in: " + result);
            assertEquals(1, requestCount.get(), "setCallServiceEnable should NOT retry");
        } finally {
            s.stop(0);
        }
    }

    // ============ MCP-R-04 timing: verify backoff actually sleeps ============

    @Test
    void safeGet_retry_applies_exponential_backoff() throws Exception {
        com.sun.net.httpserver.HttpServer s = statusOnlyServer(HttpStatus.SERVICE_UNAVAILABLE, "{\"e\":\"x\"}");
        try {
            requestCount.set(0);
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), RestClient.create());
            long start = System.nanoTime();
            svc.getAlerts("local-dev", 10);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // 100ms + 500ms = 600ms of backoff, plus 3 quick requests. Should clearly exceed 500ms.
            assertTrue(elapsedMs >= 500, "expected >=500ms of backoff across 2 retries, got: " + elapsedMs + "ms");
            assertEquals(3, requestCount.get());
        } finally {
            s.stop(0);
        }
    }

    // ============ MCP-R-04 timeout ============

    @Test
    void safeGet_hanging_server_times_out_returns_error_json() throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            // Increment BEFORE sleeping so the test can observe that the request actually arrived.
            requestCount.incrementAndGet();
            try {
                Thread.sleep(10_000); // hang longer than client timeout
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try { ex.sendResponseHeaders(200, 0); ex.getResponseBody().close(); } catch (Exception ignored) { }
        });
        s.start();
        try {
            requestCount.set(0);
            MethodTraceLogMcpProperties.HostInfo h = hostInfo(
                    "local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "");
            // Use non-retryable setCallServiceEnable so the test only waits for ONE timeout, not 3.
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(List.of(h), fastTimeoutClient(Duration.ofMillis(800)));
            long start = System.nanoTime();
            String result = svc.setCallServiceEnable("local-dev", "svc", false);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(result.contains("\"error\":"), "expected JSON error envelope, got: " + result);
            assertTrue(result.contains("TIMEOUT") || result.contains("READ") || result.contains("HOST_UNREACHABLE"),
                    "expected TIMEOUT/READ/HOST_UNREACHABLE in: " + result);
            // Single attempt: should be < 5s
            assertTrue(elapsedMs < 5_000, "should timeout fast, took: " + elapsedMs + "ms");
            assertEquals(1, requestCount.get(), "non-retryable should fire exactly 1 request");
        } finally {
            s.stop(0);
        }
    }

    // ============ MCP-R-05 / MCP-R-06 host config validation ============

    @Test
    void validateHosts_throws_on_null_hosts() {
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(null, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("at least one host"));
    }

    @Test
    void validateHosts_throws_on_empty_hosts() {
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(new ArrayList<>(), RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("at least one host"));
    }

    @Test
    void validateHosts_throws_on_duplicate_names() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("dup", "http://h1.example.com", ""),
                hostInfo("dup", "http://h2.example.com", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("Duplicate"), "expected Duplicate in: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("dup"), "expected duplicate name in: " + ex.getMessage());
    }

    @Test
    void validateHosts_throws_on_blank_name() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("", "http://h1.example.com", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("name must not be blank"));
    }

    @Test
    void validateHosts_throws_on_blank_url() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("h1", "", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("url must not be blank"));
    }

    @Test
    void validateHosts_throws_on_non_http_scheme() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("h1", "ftp://example.com/file", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("scheme"), "expected scheme error in: " + ex.getMessage());
    }

    @Test
    void validateHosts_throws_on_garbage_url() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("h1", "not a url", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("url") || ex.getMessage().contains("URI"),
                "expected URI error in: " + ex.getMessage());
    }

    @Test
    void validateHosts_throws_on_missing_host() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("h1", "http://", ""));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateHosts);
        assertTrue(ex.getMessage().contains("host"), "expected host error in: " + ex.getMessage());
    }

    @Test
    void validateHosts_passes_for_valid_config() {
        List<MethodTraceLogMcpProperties.HostInfo> hosts = List.of(
                hostInfo("a", "http://a.example.com", ""),
                hostInfo("b", "https://b.example.com:8443", "k"));
        MethodTraceLogMcpService svc = new MethodTraceLogMcpService(hosts, RestClient.create());
        svc.validateHosts(); // should not throw
    }

    // ============ POST body shape ============

    @Test
    void queryLogContent_sends_post_with_json_body() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.queryLogContent("local-dev", "app.log", "ERROR", "2026-01-01T00:00:00Z",
                "2026-01-31T23:59:59Z", 100, "ERROR");
        assertEquals("POST", lastMethod.get());
        assertEquals("/methodTraceLog/logFile/query", lastPath.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"fileName\":\"app.log\""), "expected fileName in body, got: " + body);
        assertTrue(body.contains("\"keyword\":\"ERROR\""), "expected keyword in body, got: " + body);
        assertTrue(body.contains("\"startTime\":\"2026-01-01T00:00:00Z\""), "expected startTime in body, got: " + body);
        assertTrue(body.contains("\"endTime\":\"2026-01-31T23:59:59Z\""), "expected endTime in body, got: " + body);
        assertTrue(body.contains("\"maxLines\":100"), "expected maxLines in body, got: " + body);
        assertTrue(body.contains("\"level\":\"ERROR\""), "expected level in body, got: " + body);
    }

    @Test
    void queryLogContent_omits_unset_fields_from_body() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.queryLogContent("local-dev", "app.log", null, null, null, null, null);
        String body = lastBody.get();
        assertTrue(body.contains("\"fileName\":\"app.log\""));
        assertTrue(!body.contains("keyword"), "expected keyword absent, got: " + body);
        assertTrue(!body.contains("startTime"), "expected startTime absent, got: " + body);
        assertTrue(!body.contains("endTime"), "expected endTime absent, got: " + body);
        assertTrue(!body.contains("maxLines"), "expected maxLines absent, got: " + body);
        assertTrue(!body.contains("level"), "expected level absent, got: " + body);
    }

    @Test
    void downloadLog_sends_post_to_download_path() {
        MethodTraceLogMcpService svc = newService("local-dev", "");
        svc.downloadLog("local-dev", "big.log", "OOM", null, null, null, 1000);
        assertEquals("POST", lastMethod.get());
        assertEquals("/methodTraceLog/logFile/download", lastPath.get());
        assertTrue(lastBody.get().contains("\"keyword\":\"OOM\""));
    }

    // ============ SizeLimitingClientHttpRequestFactory direct tests ============

    @Test
    void size_limiting_factory_content_length_precheck_rejects_immediately() throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            // Set a declared length larger than the cap but DO NOT write a body that large.
            ex.sendResponseHeaders(200, 10_000_000);
            ex.getResponseBody().close();
        });
        s.start();
        try {
            SizeLimitingClientHttpRequestFactory factory = new SizeLimitingClientHttpRequestFactory(
                    new JdkClientHttpRequestFactory(), 1024L);
            RestClient client = RestClient.builder().requestFactory(factory).build();
            MethodTraceLogMcpService svc = new MethodTraceLogMcpService(
                    List.of(hostInfo("local-dev", "http://127.0.0.1:" + s.getAddress().getPort(), "")),
                    client);
            String result = svc.getMethodTraceList("local-dev", null, null, null, 1);
            assertTrue(result.contains("RESPONSE_TOO_LARGE"), "expected RESPONSE_TOO_LARGE in: " + result);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void size_limiting_factory_rejects_negative_or_zero_max() {
        assertThrows(IllegalArgumentException.class,
                () -> new SizeLimitingClientHttpRequestFactory(new JdkClientHttpRequestFactory(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SizeLimitingClientHttpRequestFactory(new JdkClientHttpRequestFactory(), -1));
    }
}
