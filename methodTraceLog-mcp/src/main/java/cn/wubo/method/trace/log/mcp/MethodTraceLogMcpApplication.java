package cn.wubo.method.trace.log.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * methodTraceLog MCP 服务端入口。
 * <p>
 * 通过 stdio 与 AI Agent 通信（spring-ai-starter-mcp-server 默认 stdio transport），
 * 内部使用 RestClient 转发到已部署 methodTraceLog starter 的 host 应用。
 * <p>
 * 运行：java -jar methodTraceLog-mcp.jar
 * 配置：application.yml 中 method-trace-log.mcp.hosts
 */
@SpringBootApplication
@EnableConfigurationProperties({MethodTraceLogMcpProperties.class})
public class MethodTraceLogMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MethodTraceLogMcpApplication.class, args);
    }

    @Bean
    public RestClient mcpRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    public MethodTraceLogMcpService methodTraceLogMcpService(MethodTraceLogMcpProperties properties, RestClient mcpRestClient) {
        return new MethodTraceLogMcpService(properties.getHosts(), mcpRestClient);
    }

    @Bean
    public ToolCallbackProvider methodTraceLogMcpTools(MethodTraceLogMcpService service) {
        return MethodToolCallbackProvider.builder().toolObjects(service).build();
    }
}
