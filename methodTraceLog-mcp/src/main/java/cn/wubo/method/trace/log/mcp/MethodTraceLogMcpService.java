package cn.wubo.method.trace.log.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * methodTraceLog MCP 工具集。
 * <p>
 * 把 host（部署了 methodTraceLog starter 的应用）暴露的能力包装成 MCP @Tool，
 * 供 LLM 通过 Model Context Protocol 调用。本进程只做 HTTP 转发，不持有任何 host 状态。
 * <p>
 * 工具列表：
 * <ol>
 *   <li>{@link #getHosts} 列出全部已配置的主机</li>
 *   <li>{@link #getCallServices} 列出方法追踪日志输出服务</li>
 *   <li>{@link #setCallServiceEnable} 启用/停用某个日志服务</li>
 *   <li>{@link #getMethodTraceList} 获取最近的方法调用追踪列表</li>
 *   <li>{@link #getMethodTraceByTraceId} 根据 traceId 拉取完整调用链</li>
 *   <li>{@link #decompileMethod} 反编译指定类的指定方法</li>
 *   <li>{@link #getLogFiles} 列出日志目录下的文件</li>
 *   <li>{@link #queryLogContent} 按条件查询日志行</li>
 *   <li>{@link #downloadLog} 下载日志内容</li>
 *   <li>{@link #startMonitor} 启动文件实时监控（tail）</li>
 *   <li>{@link #stopMonitor} 停止文件实时监控</li>
 *   <li>{@link #getMonitorStatus} 查看监控状态</li>
 *   <li>{@link #ping} 健康检查：是否可连通</li>
 * </ol>
 */
public class MethodTraceLogMcpService {

    private final List<MethodTraceLogMcpProperties.HostInfo> hosts;
    private final RestClient restClient;

    public MethodTraceLogMcpService(List<MethodTraceLogMcpProperties.HostInfo> hosts, RestClient restClient) {
        this.hosts = hosts;
        this.restClient = restClient;
    }

    // ===================== host 维度 =====================

    @Tool(description = "获取所有已配置的方法追踪日志主机列表（主机名 + 描述 + URL）")
    public String getHosts() {
        if (hosts == null || hosts.isEmpty()) {
            return "未配置任何主机。请在 methodTraceLog-mcp 的 application.yml 中配置 method-trace-log.mcp.hosts。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("主机列表：\n");
        for (MethodTraceLogMcpProperties.HostInfo host : hosts) {
            sb.append(String.format("- 主机名称：%s | 描述：%s | URL：%s%n",
                    nullSafe(host.getName()), nullSafe(host.getDescription()), nullSafe(host.getUrl())));
        }
        return sb.toString();
    }

    @Tool(description = "健康检查：尝试访问主机的 actuator endpoints，验证网络与鉴权是否通畅")
    public String ping(@ToolParam(description = "主机名称（与配置中的 name 一致）") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(), "/actuator", String.class);
    }

    // ===================== 日志服务（CallService）维度 =====================

    @Tool(description = "列出主机上注册的全部方法调用日志输出服务及其启用状态")
    public String getCallServices(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(), "/methodTraceLog/view/callServices", String.class);
    }

    @Tool(description = "启用或停用指定的日志服务（控制是否输出方法追踪日志）")
    public String setCallServiceEnable(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "服务名称（从 getCallServices 获取）") String serviceName,
            @ToolParam(description = "true=启用；false=停用") boolean enable) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(),
                "/methodTraceLog/view/callService?name=" + url(serviceName) + "&enable=" + enable,
                String.class);
    }

    // ===================== 方法追踪（trace）维度 =====================

    @Tool(description = "获取最近的方法调用追踪记录列表（监控面板 list 视图）")
    public String getMethodTraceList(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(), "/methodTraceLog/view/list", String.class);
    }

    @Tool(description = "根据 traceId 拉取完整的方法调用链（span 树）")
    public String getMethodTraceByTraceId(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "追踪 ID（来自一次方法调用的根节点）") String traceId) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(),
                "/methodTraceLog/view/traceid?id=" + url(traceId),
                String.class);
    }

    // ===================== 反编译维度 =====================

    @Tool(description = "反编译指定类的指定方法并返回去注解后的 Java 源码（用于阅读第三方或历史代码）")
    public String decompileMethod(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "类的全限定名，如 java.lang.String") String className,
            @ToolParam(description = "方法名（不支持重载同名同时返回）") String methodName,
            @ToolParam(description = "可选：反编译超时秒数，默认 10", required = false) Long timeoutSeconds) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        StringBuilder path = new StringBuilder("/methodTraceLog/decompile?className=")
                .append(url(className)).append("&methodName=").append(url(methodName));
        if (timeoutSeconds != null) path.append("&timeoutSeconds=").append(timeoutSeconds);
        return doGet(host.get(), path.toString(), String.class);
    }

    // ===================== 日志文件维度 =====================

    @Tool(description = "列出主机配置的日志目录下的日志文件")
    public String getLogFiles(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(), "/methodTraceLog/logFile/files", String.class);
    }

    @Tool(description = "在指定日志文件中按关键字与时间范围查询匹配行（最近若干行/从尾部起）")
    public String queryLogContent(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "日志文件名") String fileName,
            @ToolParam(description = "可选：关键字过滤") String keyword,
            @ToolParam(description = "可选：起始时间 ISO8601") String startTime,
            @ToolParam(description = "可选：结束时间 ISO8601") String endTime,
            @ToolParam(description = "可选：返回最大行数，默认 200") Integer maxLines,
            @ToolParam(description = "可选：日志级别过滤，如 INFO/ERROR/DEBUG") String level) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";

        // POST /methodTraceLog/logFile/query with JSON body
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileName", fileName);
        if (keyword != null) body.put("keyword", keyword);
        if (startTime != null) body.put("startTime", startTime);
        if (endTime != null) body.put("endTime", endTime);
        if (maxLines != null) body.put("maxLines", maxLines);
        if (level != null) body.put("level", level);

        return doPost(host.get(), "/methodTraceLog/logFile/query", body, String.class);
    }

    @Tool(description = "下载（返回文本）指定日志文件的全部内容或匹配段")
    public String downloadLog(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "日志文件名") String fileName,
            @ToolParam(description = "可选：关键字过滤") String keyword,
            @ToolParam(description = "可选：起始时间 ISO8601") String startTime,
            @ToolParam(description = "可选：结束时间 ISO8601") String endTime,
            @ToolParam(description = "可选：日志级别过滤") String level,
            @ToolParam(description = "可选：最大行数") Integer maxLines) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileName", fileName);
        if (keyword != null) body.put("keyword", keyword);
        if (startTime != null) body.put("startTime", startTime);
        if (endTime != null) body.put("endTime", endTime);
        if (level != null) body.put("level", level);
        if (maxLines != null) body.put("maxLines", maxLines);

        return doPost(host.get(), "/methodTraceLog/logFile/download", body, String.class);
    }

    // ===================== 实时监控维度 =====================

    @Tool(description = "开始实时监控（tail）指定的日志文件。新增的行会通过 WebSocket 推送到前端页")
    public String startMonitor(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "要监控的日志文件名") String fileName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(),
                "/methodTraceLog/logFile/monitor/start?fileName=" + url(fileName),
                String.class);
    }

    @Tool(description = "停止实时监控指定的日志文件")
    public String stopMonitor(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "要停止监控的日志文件名") String fileName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(),
                "/methodTraceLog/logFile/monitor/stop?fileName=" + url(fileName),
                String.class);
    }

    @Tool(description = "查看主机的实时日志监控状态（当前监控的文件、是否在监控、监控文件数）")
    public String getMonitorStatus(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return doGet(host.get(), "/methodTraceLog/logFile/monitor/status", String.class);
    }

    // ===================== 内部辅助 =====================

    private Optional<MethodTraceLogMcpProperties.HostInfo> findHost(String name) {
        if (hosts == null) return Optional.empty();
        return hosts.stream()
                .filter(h -> h.getName() != null && h.getName().equals(name))
                .findAny();
    }

    private <T> T doGet(MethodTraceLogMcpProperties.HostInfo host, String path, Class<T> responseType) {
        return restClient.get()
                .uri(host.getUrl() + path)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                .headers(h -> { if (notBlank(host.getApiKey())) h.set("X-Api-Key", host.getApiKey()); })
                .retrieve()
                .body(responseType);
    }

    private <T> T doGet(MethodTraceLogMcpProperties.HostInfo host, String path, ParameterizedTypeReference<T> typeRef) {
        return restClient.get()
                .uri(host.getUrl() + path)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                .headers(h -> { if (notBlank(host.getApiKey())) h.set("X-Api-Key", host.getApiKey()); })
                .retrieve()
                .body(typeRef);
    }

    private <T> T doPost(MethodTraceLogMcpProperties.HostInfo host, String path, Object body, Class<T> responseType) {
        return restClient.post()
                .uri(host.getUrl() + path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                .headers(h -> { if (notBlank(host.getApiKey())) h.set("X-Api-Key", host.getApiKey()); })
                .body(body)
                .retrieve()
                .body(responseType);
    }

    private static String url(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
