package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.MethodTraceLogTestApplication;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MtlE2eHarness implements AutoCloseable {

    private static final String API_KEY = "change-me-in-production";

    private final ConfigurableApplicationContext primary;
    private final TestRestTemplate http;
    private final int primaryPort;

    private MtlE2eHarness(ConfigurableApplicationContext ctx) {
        this.primary = ctx;
        this.primaryPort = ctx.getEnvironment().getProperty("server.port", Integer.class, 8085);
        RestTemplateBuilder b = new RestTemplateBuilder()
                .basicAuthentication("", API_KEY); // placeholder; we add X-Api-Key below
        this.http = new TestRestTemplate(b);
        // Add X-Api-Key default header
        this.http.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("X-Api-Key", API_KEY);
            return execution.execute(request, body);
        });
    }

    public static MtlE2eHarness primary(int port, Map<String, Object> extraProps) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("server.port", port);
        defaults.put("management.endpoints.web.exposure.include", "methodtrace,health,metrics");
        defaults.put("method-trace-log.security.api-key", API_KEY);
        defaults.put("logging.file.name", "logs/app-a.log");
        if (extraProps != null) defaults.putAll(extraProps);
        ConfigurableApplicationContext ctx = SpringApplication.run(MethodTraceLogTestApplication.class, toArgs(defaults));
        return new MtlE2eHarness(ctx);
    }

    public static MtlE2eHarness secondary(int port) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("logging.file.name", "logs/app-b.log");
        return primary(port, extra);
    }

    private static String[] toArgs(Map<String, Object> props) {
        return props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
    }

    public TestRestTemplate http() { return http; }
    public int port() { return primaryPort; }

    /**
     * Exposes the underlying Spring {@link ConfigurableApplicationContext} so callers
     * (notably {@code OtelPropagationIT}) can retrieve beans by type — e.g. to install
     * the OTel SDK bean into {@link io.opentelemetry.api.GlobalOpenTelemetry}.
     * Added in Round 10 to enable the OTel propagation integration test without
     * touching starter code.
     */
    public ConfigurableApplicationContext context() { return primary; }

    public MethodTraceInfo awaitTrace(String traceId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = http.exchange(
                        "http://localhost:" + primaryPort + "/methodTraceLog/view/traceid?id=" + traceId,
                        HttpMethod.GET, HttpEntity.EMPTY, MethodTraceInfo.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) return resp.getBody();
            } catch (Exception ignored) { /* not yet */ }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError("Trace " + traceId + " did not appear within " + timeout);
    }

    public List<MethodTraceInfo> awaitTraceList(int minCount, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                @SuppressWarnings("unchecked")
                var arr = (List<MethodTraceInfo>) http.exchange(
                        "http://localhost:" + primaryPort + "/methodTraceLog/view/list?limit=" + Math.max(minCount * 2, 50),
                        HttpMethod.GET, HttpEntity.EMPTY, List.class).getBody();
                if (arr != null && arr.size() >= minCount) return arr;
            } catch (Exception ignored) { /* not yet */ }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError("Trace list did not reach " + minCount + " within " + timeout);
    }

    public List<Map<String, Object>> awaitWebhook(int minCount, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) http.exchange(
                    "http://localhost:" + primaryPort + "/test/_test/echo-webhook",
                    HttpMethod.GET, HttpEntity.EMPTY, List.class).getBody();
            if (list != null && list.size() >= minCount) return list;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError("Webhook did not receive " + minCount + " within " + timeout);
    }

    public void clearWebhook() {
        http.exchange("http://localhost:" + primaryPort + "/test/_test/echo-webhook",
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
    }

    /**
     * Walks the call tree depth-first looking for a node whose BEFORE event has the
     * given method name. Note: MethodTraceInfo itself doesn't carry className/methodName
     * directly — those live on its {@code before}/{@code after} ServiceCallInfo snapshots.
     */
    public Optional<MethodTraceInfo> findInTrace(MethodTraceInfo root, String methodName) {
        if (root == null || methodName == null) return Optional.empty();
        if (root.getBefore() != null && methodName.equals(root.getBefore().getMethodName())) {
            return Optional.of(root);
        }
        if (root.getChildren() != null) {
            for (var child : root.getChildren()) {
                var found = findInTrace(child, methodName);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns true iff the root subtree contains a direct-or-indirect child whose
     * BEFORE event matches {@code childClass#childMethod}. Class/method name live on
     * the BEFORE ServiceCallInfo, not on MethodTraceInfo directly.
     */
    public boolean traceContainsChildOf(MethodTraceInfo root, String childClass, String childMethod) {
        if (root == null || root.getChildren() == null) return false;
        for (var child : root.getChildren()) {
            if (child.getBefore() != null
                    && childClass.equals(child.getBefore().getClassName())
                    && childMethod.equals(child.getBefore().getMethodName())) {
                return true;
            }
            if (traceContainsChildOf(child, childClass, childMethod)) return true;
        }
        return false;
    }

    @Override
    public void close() {
        if (primary != null) primary.close();
    }
}