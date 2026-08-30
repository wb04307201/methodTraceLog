package cn.wubo.method.trace.log.mcp;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * methodTraceLog MCP 服务端入口。
 * <p>
 * 通过 stdio 与 AI Agent 通信（spring-ai-starter-mcp-server 默认 stdio transport），
 * 内部使用 RestClient 转发到已部署 methodTraceLog starter 的 host 应用。
 * <p>
 * 运行：java -jar methodTraceLog-mcp.jar
 * 配置：application.yml 中 method-trace-log.mcp.hosts
 *
 * <p><b>Round 14 hardening:</b>
 * <ul>
 *   <li>Two {@link RestClient} beans (fast / long) with explicit timeouts:
 *       <ul>
 *           <li>fast: 3s connect, 30s read (default for most tools)</li>
 *           <li>long: 3s connect, 120s read (downloadLog, decompileMethod)</li>
 *       </ul>
 *   </li>
 *   <li>Both clients cap the in-memory response body size at 16 MiB
 *       via {@link SizeLimitingClientHttpRequestFactory}, a delegating
 *       {@link ClientHttpRequestFactory} wrapper that enforces the limit at the
 *       {@code Content-Length} pre-check and via streaming byte-counter.</li>
 *   <li>The {@link MethodTraceLogMcpService} bean validates every
 *       {@link MethodTraceLogMcpProperties.HostInfo} at startup (non-blank name, parseable
 *       {@code http}/{@code https} URL with a host component, unique names) and fails the
 *       context boot loudly on the first invalid entry.</li>
 * </ul>
 *
 * <p><b>Round 15 hardening:</b>
 * <ul>
 *   <li>(MCP-R-08) Switched from {@code JdkClientHttpRequestFactory} (no close lifecycle) to
 *       {@link HttpComponentsClientHttpRequestFactory} backed by an Apache HttpClient 5
 *       {@link CloseableHttpClient} bean with {@code @Bean(destroyMethod = "close")}.
 *       On context shutdown the connection pool is released cleanly instead of leaking
 *       until the JVM dies. Spring's per-phase shutdown timeout is set to 30s in
 *       {@code application.yml} ({@code spring.lifecycle.timeout-per-shutdown-phase}).</li>
 *   <li>(MCP-R-09) {@code @PostConstruct validateHosts()} now logs a WARN for each host whose
 *       {@code url.startsWith("http://")} <em>and</em> {@code apiKey} is non-empty, so sending
 *       a static API key in cleartext over the wire surfaces loudly at startup instead of
 *       silently leaking in production logs / traces.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties({MethodTraceLogMcpProperties.class})
public class MethodTraceLogMcpApplication {

    private static final Logger log = LoggerFactory.getLogger(MethodTraceLogMcpApplication.class);

    /**
     * Connect timeout (shared by both fast/long clients).
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Read timeout for "fast" tools (most read-only GETs).
     */
    static final Duration READ_TIMEOUT_FAST = Duration.ofSeconds(30);

    /**
     * Read timeout for "long" tools ({@code downloadLog}, {@code decompileMethod},
     * and any other potentially-slow host-side operation).
     */
    static final Duration READ_TIMEOUT_LONG = Duration.ofSeconds(120);

    /**
     * Maximum in-memory response body size, in bytes (16 MiB). Enforced by
     * {@link SizeLimitingClientHttpRequestFactory} via {@code Content-Length} pre-check and a
     * streaming byte-counter. The Apache HttpClient 5 default {@code -Djdk.httpclient.maxMemBufSize}
     * equivalent is the connection-manager's {@code maxConnTotal} / {@code maxConnPerRoute}, which
     * does not bound body size, so the 16 MiB ceiling on {@code RestClient} is the practical upper
     * bound for any single HTTP response regardless of the host's behaviour.
     */
    static final int MAX_RESPONSE_SIZE_BYTES = 16 * 1024 * 1024;

    public static void main(String[] args) {
        SpringApplication.run(MethodTraceLogMcpApplication.class, args);
    }

    // ===================== MCP-R-08: graceful-shutdown wiring =====================

    /**
     * The {@link CloseableHttpClient} shared by both RestClient beans. {@code destroyMethod = "close"}
     * tells Spring to call {@link CloseableHttpClient#close()} on context shutdown, releasing the
     * connection pool. Without this the JDK-backed {@code JdkClientHttpRequestFactory} would hold its
     * threads / selectors open until the JVM died — exactly the leak MCP-R-08 flagged.
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient mcpCloseableHttpClient() {
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setSocketTimeout(Timeout.ZERO_MILLISECONDS) // bound by per-request timeout below
                        .build())
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();
        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .build())
                .disableAutomaticRetries() // we handle retries at the service layer
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();
    }

    @Bean("mcpRestClientFast")
    public RestClient mcpRestClientFast(@Qualifier("mcpCloseableHttpClient") HttpClient httpClient) {
        return buildRestClient(httpClient, READ_TIMEOUT_FAST);
    }

    @Bean("mcpRestClientLong")
    public RestClient mcpRestClientLong(@Qualifier("mcpCloseableHttpClient") HttpClient httpClient) {
        return buildRestClient(httpClient, READ_TIMEOUT_LONG);
    }

    @Bean
    public MethodTraceLogMcpService methodTraceLogMcpService(
            MethodTraceLogMcpProperties properties,
            @Qualifier("mcpRestClientFast") RestClient mcpRestClientFast,
            @Qualifier("mcpRestClientLong") RestClient mcpRestClientLong) {
        return new MethodTraceLogMcpService(properties.getHosts(), mcpRestClientFast, mcpRestClientLong);
    }

    @Bean
    public ToolCallbackProvider methodTraceLogMcpTools(MethodTraceLogMcpService service) {
        return MethodToolCallbackProvider.builder().toolObjects(service).build();
    }

    /**
     * Build a {@link RestClient} with the given read timeout and response-size cap, backed by
     * the shared Apache HttpClient 5 client.
     */
    private static RestClient buildRestClient(HttpClient httpClient, Duration readTimeout) {
        HttpComponentsClientHttpRequestFactory hcFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        hcFactory.setReadTimeout(readTimeout);

        ClientHttpRequestFactory sizeLimiting = new SizeLimitingClientHttpRequestFactory(hcFactory, MAX_RESPONSE_SIZE_BYTES);

        return RestClient.builder()
                .requestFactory(sizeLimiting)
                .build();
    }
}
