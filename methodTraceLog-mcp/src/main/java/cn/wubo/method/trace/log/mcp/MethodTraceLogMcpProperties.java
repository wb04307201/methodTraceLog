package cn.wubo.method.trace.log.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * methodTraceLog MCP 客户端配置。
 * <p>
 * 通过 method-trace-log.mcp.hosts 配置需要被 MCP 工具访问的主机列表。
 * 每个 host 对应一个部署了 methodTraceLog starter 的 Spring Boot 应用。
 */
@Data
@ConfigurationProperties(prefix = "method-trace-log.mcp")
public class MethodTraceLogMcpProperties {

    /**
     * 目标主机列表（已部署 methodTraceLog starter 的 Spring Boot 应用）。
     */
    private List<HostInfo> hosts = new ArrayList<>();

    @Data
    public static class HostInfo {
        /**
         * 主机名（在 MCP 工具调用中作为 systemName 参数使用）。
         */
        private String name;

        /**
         * 主机根 URL，如 http://localhost:8080
         */
        private String url;

        /**
         * 主机描述。
         */
        private String description;

        /**
         * 主机配置的安全 API Key（若启用 method-trace-log.security.api-key）。
         * 留空表示该主机未启用鉴权。
         */
        private String apiKey = "";
    }
}
