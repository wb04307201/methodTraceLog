package cn.wubo.method.trace.log.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
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
 */
@SpringBootApplication
@EnableConfigurationProperties({MethodTraceLogMcpProperties.class})
public class MethodTraceLogMcpApplication {

    /**
     * Connect timeout (shared by both fast/long clients via the underlying {@link HttpClient}).
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
     * streaming byte-counter. The JDK {@link HttpClient} also has a built-in
     * {@code -Djdk.httpclient.maxMemBufSize} ceiling (default 16 MiB for unconfigured JVMs), so
     * 16 MiB is the practical upper bound for any single HTTP response regardless of our cap.
     */
    static final int MAX_RESPONSE_SIZE_BYTES = 16 * 1024 * 1024;

    public static void main(String[] args) {
        SpringApplication.run(MethodTraceLogMcpApplication.class, args);
    }

    @Bean("mcpRestClientFast")
    public RestClient mcpRestClientFast() {
        return buildRestClient(READ_TIMEOUT_FAST, MAX_RESPONSE_SIZE_BYTES);
    }

    @Bean("mcpRestClientLong")
    public RestClient mcpRestClientLong() {
        return buildRestClient(READ_TIMEOUT_LONG, MAX_RESPONSE_SIZE_BYTES);
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
     * Build a {@link RestClient} with the given read timeout and response-size cap.
     * Connect timeout is enforced by the shared underlying {@link HttpClient}; read
     * timeout is set on the factory.
     */
    private static RestClient buildRestClient(Duration readTimeout, int maxResponseSizeBytes) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory jdkFactory = new JdkClientHttpRequestFactory(httpClient);
        jdkFactory.setReadTimeout(readTimeout);

        ClientHttpRequestFactory sizeLimiting = new SizeLimitingClientHttpRequestFactory(jdkFactory, maxResponseSizeBytes);

        return RestClient.builder()
                .requestFactory(sizeLimiting)
                .build();
    }
}
