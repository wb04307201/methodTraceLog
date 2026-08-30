package cn.wubo.method.trace.log.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link MethodTraceLogMcpApplication} refuses to start the context when
 * {@code method-trace-log.mcp.hosts} has invalid entries — drives a real
 * {@link SpringApplicationBuilder#run()} with a temporary YAML file that fully replaces
 * {@code application.yml} (we override {@code spring.config.location}).
 * <p>
 * MCP-R-05: empty hosts / duplicate host names → context fails.
 * MCP-R-06: non-parseable URL / non-http scheme → context fails.
 */
class MethodTraceLogMcpStartupValidationTest {

    private void assertContextFailsToStart(Map<String, Object> props, String expectedInMessage) throws IOException {
        Path config = Files.createTempFile("mtl-mcp-test-config-", ".yml");
        try {
            writeYamlHosts(config, props);
            Throwable thrown = assertThrows(Throwable.class, () -> {
                try (ConfigurableApplicationContext ctx = builder(config).run()) {
                    assertNotNull(ctx); // should never reach here
                }
            });
            Throwable t = thrown;
            boolean matched = false;
            int depth = 0;
            while (t != null && depth < 16) {
                if (t.getMessage() != null && t.getMessage().contains(expectedInMessage)) {
                    matched = true;
                    break;
                }
                t = t.getCause();
                depth++;
            }
            assertTrue(matched, () ->
                    "expected startup failure with message containing '" + expectedInMessage +
                            "', got: " + thrown);
        } finally {
            Files.deleteIfExists(config);
        }
    }

    /**
     * Write a tiny YAML config to {@code path} that pins {@code method-trace-log.mcp.hosts}
     * to the supplied list (and disables / overrides anything from {@code application.yml}).
     */
    private static void writeYamlHosts(Path path, Map<String, Object> props) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println("spring:");
            w.println("  main:");
            w.println("    web-application-type: none");
            w.println("    banner-mode: off");
            w.println("  ai:");
            w.println("    mcp:");
            w.println("      server:");
            w.println("        stdio: false");
            w.println("        name: mcp-test");
            w.println("method-trace-log:");
            w.println("  mcp:");
            w.println("    hosts:");
            Object rawHosts = props.get("hosts");
            if (rawHosts == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hosts = (List<Map<String, Object>>) rawHosts;
            for (Map<String, Object> host : hosts) {
                w.println("      - name: " + yamlScalar(host.get("name")));
                w.println("        url: " + yamlScalar(host.get("url")));
                w.println("        description: " + yamlScalar(host.get("description")));
                w.println("        api-key: " + yamlScalar(host.get("api-key")));
            }
        }
    }

    /**
     * YAML-safe scalar for a single string (no flow / block style; just quotes if needed).
     * Anything but the simple "word-only" pattern gets double-quoted with embedded double quotes
     * escaped — sufficient for our literal test inputs.
     */
    private static String yamlScalar(Object o) {
        String s = o == null ? "" : o.toString();
        if (s.matches("^[A-Za-z0-9._/-]+$")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Build the Spring application that boots {@link MethodTraceLogMcpApplication} with the
     * given temp config as the sole configuration source.
     */
    private static SpringApplicationBuilder builder(Path config) {
        return new SpringApplicationBuilder(MethodTraceLogMcpApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties(
                        "spring.config.location=" + config.toUri().toString(),
                        "spring.config.name=ignored");
    }

    // ============ MCP-R-05 ============

    @Test
    void context_fails_when_hosts_list_is_empty() throws IOException {
        assertContextFailsToStart(Map.of("hosts", new ArrayList<>()), "at least one host");
    }

    @Test
    void context_fails_on_duplicate_host_names() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(
                Map.of("name", "dup", "url", "http://h1.example.com",
                        "description", "", "api-key", ""),
                Map.of("name", "dup", "url", "http://h2.example.com",
                        "description", "", "api-key", "")));
        assertContextFailsToStart(props, "Duplicate host name 'dup'");
    }

    // ============ MCP-R-06 ============

    @Test
    void context_fails_on_unparseable_url() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(Map.of("name", "h1", "url", "not a url",
                "description", "", "api-key", "")));
        assertContextFailsToStart(props, "url is not a valid URI");
    }

    @Test
    void context_fails_on_non_http_scheme() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(Map.of("name", "h1", "url", "ftp://example.com/file",
                "description", "", "api-key", "")));
        assertContextFailsToStart(props, "scheme must be http or https");
    }

    @Test
    void context_fails_on_blank_host_name() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(Map.of("name", "  ", "url", "http://h1.example.com",
                "description", "", "api-key", "")));
        assertContextFailsToStart(props, "name must not be blank");
    }

    @Test
    void context_fails_on_missing_host_component() throws IOException {
        // "http://" parses to a URI but has no host component. We catch it either at URI.create()
        // (where Java's URI rejects the input) or in the explicit host-component check.
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(Map.of("name", "h1", "url", "http://",
                "description", "", "api-key", "")));
        assertContextFailsToStart(props, "url");  // matches both error paths
    }

    // ============ smoke ============

    @Test
    void context_boots_with_valid_hosts() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hosts", List.of(Map.of("name", "valid", "url", "http://valid.example.com",
                "description", "", "api-key", "")));
        Path config = Files.createTempFile("mtl-mcp-test-config-valid-", ".yml");
        try {
            writeYamlHosts(config, props);
            try (ConfigurableApplicationContext ctx = builder(config).run()) {
                assertNotNull(ctx);
                assertNotNull(ctx.getBean(MethodTraceLogMcpService.class));
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }
}
