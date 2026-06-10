package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.*;

@Data
@ConfigurationProperties(prefix = "method-trace-log")
public class MethodTraceLogProperties {

    @NestedConfigurationProperty
    private LogProperties log = new LogProperties();

    @NestedConfigurationProperty
    private FileProperties file = new FileProperties();

    /**
     * 安全配置：API Key 用于保护 /methodTraceLog/** 反编译等敏感端点。
     * 留空表示关闭鉴权（仅限开发环境）。生产环境务必配置。
     */
    @NestedConfigurationProperty
    private SecurityProperties security = new SecurityProperties();

    /**
     * 反编译相关配置。
     */
    @NestedConfigurationProperty
    private DecompileProperties decompile = new DecompileProperties();

    /**
     * OpenTelemetry 导出配置。导出器为 ICallService，OTLP/HTTP 协议。
     * 仅当 classpath 上有 {@code io.opentelemetry:opentelemetry-sdk} 时才注册。
     */
    @NestedConfigurationProperty
    private OtelProperties otel = new OtelProperties();

    /**
     * 跨线程/跨服务 trace 传播配置。
     */
    @NestedConfigurationProperty
    private PropagateProperties propagate = new PropagateProperties();

    @Data
    public static class LogProperties {
        private Boolean enable = true;

        private List<ServiceCallProperties> serviceCalls = new ArrayList<>();

        /**
         * 根调用采样率，[0.0, 1.0]。1.0 = 全部采样（默认），0.0 = 全部丢弃。
         * 子调用自动继承父调用的采样决定。
         */
        private Double sampleRate = 1.0;

        /**
         * trace 持久化配置。
         */
        private TraceStoreProperties traceStore = new TraceStoreProperties();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ServiceCallProperties {
            private String name;
            private Boolean enable = true;
        }
    }

    @Data
    public static class TraceStoreProperties {
        /**
         * 存储类型：in-memory（默认，进程内 ConcurrentHashMap）
         *          file（每条根 trace 落盘为 JSON 文件，适合长时间跑/避免 OOM）
         *          none（不存储，Micrometer 指标仍然写入）
         */
        private String type = "in-memory";

        /**
         * 仅当 type=file 时生效：根目录。会自动按 yyyy-MM-dd 建子目录。
         */
        private String path = "./trace-store";

        /**
         * 根 trace 在内存中保留的最大数量。超出按写入时间淘汰最旧。
         * 仅影响 in-memory 与 file 的内存缓存。
         */
        private int maxTraces = 1000;

        /**
         * 过期时长（毫秒）。clean() 会删除超过此时间的磁盘文件。
         * 默认 8 小时。
         */
        private Long ttlMillis = 8L * 60 * 60 * 1000L;

        /**
         * 仅当 type=file 时生效：启动时扫描目录重建 traceId → file 索引。
         * 文件量大时会拖慢启动；保持默认 false。
         */
        private boolean rebuildIndexOnStart = false;
    }

    @Data
    public static class FileProperties {

        private Boolean enable = true;

        /**
         * 日志文件根目录
         */
        private String logPath = "./logs";

        /**
         * 允许访问的日志文件扩展名
         */
        private List<String> allowedExtensions = Arrays.asList(".log", ".txt", ".out");

        /**
         * 单次查询最大行数（同时也是 query 路径下流式扫描的最大行数）
         */
        private int maxLines = 1000;

        /**
         * 文件最大大小（MB）
         */
        private long maxFileSize = 100;

        /**
         * 日志文件匹配模式
         */
        private String logPattern = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s*-\\s*(.*)";
    }

    @Data
    public static class SecurityProperties {
        /**
         * API Key。若为空字符串或 null 则关闭鉴权（仅供开发/本地）。
         * 鉴权生效后，浏览器可通过 cookie 鉴权，CLI / MCP 可继续用 X-Api-Key header。
         */
        private String apiKey = "";

        /**
         * 浏览器端 cookie 会话配置。
         */
        private SessionProperties session = new SessionProperties();
    }

    @Data
    public static class SessionProperties {
        /**
         * 会话有效期。超过此时间未访问则失效。
         */
        private long ttlMillis = 8L * 60 * 60 * 1000L;
    }

    @Data
    public static class DecompileProperties {
        /**
         * 反编译单次调用的默认超时（秒）。CFR 在病态输入下可能长时间运行。
         * 最小 1 秒（0 秒会导致永远 timeout 必抛 IllegalStateException）。
         */
        private long timeoutSeconds = 10L;
    }

    @Data
    public static class OtelProperties {
        /**
         * 是否注册 OTLP/HTTP Span 导出器作为 ICallService。
         * 即使开启，也只在 trace 被采样时才会发送（由 LogAspect 短路未被采样的 trace）。
         */
        private boolean enable = false;

        /**
         * OTLP HTTP endpoint，例如 http://localhost:4318/v1/traces。
         */
        private String endpoint = "http://localhost:4318/v1/traces";

        /**
         * Resource service.name 标签。
         */
        private String serviceName = "method-trace-log";

        /**
         * Resource service.namespace 标签。
         */
        private String serviceNamespace = "";

        /**
         * 批量导出延迟（毫秒）。OTLP 客户端内置 batching。
         */
        private long exportDelayMillis = 5000L;

        /**
         * 批量导出最大队列大小。
         */
        private int maxQueueSize = 2048;

        /**
         * 单批最大导出跨度。
         */
        private int maxExportBatchSize = 512;

        /**
         * 导出超时（毫秒）。
         */
        private long exportTimeoutMillis = 30000L;
    }

    @Data
    public static class PropagateProperties {
        /**
         * 注册 TraceContextFilter：从 HTTP 请求的 traceparent 头恢复 traceid/parentId/sampled。
         */
        private boolean httpInbound = true;

        /**
         * 给 RestClient.Builder 注册拦截器：自动给所有出站 HTTP 请求注入 traceparent。
         */
        private boolean restClientOutbound = true;

        /**
         * 是否提供 TraceContextRestTemplateInterceptor Bean。
         * 用户需要主动把它设置到自己的 RestTemplate 上。
         */
        private boolean restTemplateInterceptor = true;
    }
}
