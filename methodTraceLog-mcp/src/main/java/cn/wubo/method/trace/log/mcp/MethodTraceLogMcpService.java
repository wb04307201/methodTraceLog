package cn.wubo.method.trace.log.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
 *   <li>{@link #getAlerts} 获取主机近期触发的告警事件</li>
 *   <li>{@link #getSlowMethods} 获取主机上调用最慢的方法 Top-N</li>
 *   <li>{@link #decompileMethod} 反编译指定类的指定方法</li>
 *   <li>{@link #getLogFiles} 列出日志目录下的文件</li>
 *   <li>{@link #queryLogContent} 按条件查询日志行</li>
 *   <li>{@link #downloadLog} 下载日志内容</li>
 *   <li>{@link #startMonitor} 启动文件实时监控（tail）</li>
 *   <li>{@link #stopMonitor} 停止文件实时监控</li>
 *   <li>{@link #getMonitorStatus} 查看监控状态</li>
 *   <li>{@link #ping} 健康检查：是否可连通</li>
 * </ol>
 *
 * <p><b>Round 14 hardening:</b>
 * <ul>
 *   <li>Two-arg + three-arg constructors: the three-arg form takes a fast {@link RestClient}
 *       (30s read) and a long {@link RestClient} (120s read); the two-arg form keeps the same
 *       {@link RestClient} for both (used by tests / manual wiring).</li>
 *   <li>All host calls go through {@link #safeGet} / {@link #safePost} which catch
 *       {@link RestClientException}, classify the failure, and return a structured
 *       JSON error string (instead of letting the stack trace bubble up to the LLM).</li>
 *   <li>Idempotent GETs retry up to 2 times with exponential backoff (100ms → 500ms);
 *       mutating ops and POSTs do <em>not</em> retry.</li>
 *   <li>{@code @PostConstruct validateHosts()} rejects null / blank / duplicate host
 *       entries and any URL that is not a parseable {@code http} or {@code https} URI with a host component.</li>
 *   <li>Query parameter encoding uses RFC 3986-style path encoding
 *       ({@code URLEncoder.encode(s, UTF_8).replace("+", "%20")}) rather than
 *       form-encoded {@code URLEncoder} output.</li>
 * </ul>
 *
 * <p><b>Round 15 hardening:</b>
 * <ul>
 *   <li>(MCP-R-09) {@link #validateHosts()} logs a WARN for every host whose {@code url} starts
 *       with {@code http://} <em>and</em> whose {@code apiKey} is non-empty, so sending a static
 *       API key in cleartext over the wire surfaces loudly at startup instead of silently
 *       leaking in production logs / traces.</li>
 *   <li>(MCP-R-13) {@link #ping} now tries {@code /actuator/health} first and falls back to
 *       {@code /methodTraceLog/view/callServices} as a smoke test, so hosts that do not expose
 *       the bare {@code /actuator} endpoint still report healthy.</li>
 *   <li>(MCP-R-16) {@link #getMethodTraceList} no longer emits a trailing {@code ?} when all
 *       filters are {@code null}.</li>
 *   <li>(MCP-R-17) {@link #getMethodTraceByTraceId} validates {@code traceId} against
 *       {@link #TRACE_ID_PATTERN} ({@code ^[A-Za-z0-9_-]{1,128}$}); 1 MB strings and slashes
 *       now produce a friendly error instead of round-tripping to the host.</li>
 *   <li>(MCP-R-18) {@link #queryLogContent} / {@link #downloadLog} clamp
 *       {@code fileName} ≤ {@link #MAX_FILENAME_LENGTH} (256) chars,
 *       {@code keyword} ≤ {@link #MAX_KEYWORD_LENGTH} (1024) chars, and validate
 *       {@code startTime} / {@code endTime} against {@link #ISO_8601_PATTERN}.</li>
 *   <li>(MCP-R-19) {@link #safeGet} / {@link #safePost} now record which {@link RestClient}
 *       (fast / long) was used on the most recent call ({@link #wasLastCallLongClient()} +
 *       {@link #getLastCallStats()}); routing is verified by {@code MethodTraceLogMcpServiceTest}.</li>
 *   <li>(MCP-R-20) Every tool invocation emits a single structured INFO audit line on
 *       {@code stderr} (via the SLF4J logger, which {@code logback-spring.xml} routes to
 *       {@code System.err}) in the form
 *       {@code tool=... host=... path=... status=... duration=NNms}.</li>
 * </ul>
 */
public class MethodTraceLogMcpService {

    private static final Logger log = LoggerFactory.getLogger(MethodTraceLogMcpService.class);

    /**
     * SLF4J logger name for the structured audit trail. Written to {@code stderr} via
     * {@code logback-spring.xml}; never to {@code stdout} (which is reserved for JSON-RPC).
     */
    static final String AUDIT_LOGGER_NAME = "mcp.audit";

    /**
     * Dedicated audit logger so the {@code audit} lines are easy to filter / ship to a SIEM.
     */
    private static final Logger auditLog = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    /**
     * Initial retry backoff in milliseconds. Subsequent retries multiply by
     * {@link #RETRY_BACKOFF_MULTIPLIER}.
     */
    static final long RETRY_BACKOFF_INITIAL_MS = 100L;

    /**
     * Retry backoff multiplier between attempts (initial 100ms → 500ms).
     */
    static final long RETRY_BACKOFF_MULTIPLIER = 5;

    /**
     * Total attempts for retryable operations (1 initial + 2 retries = 3).
     */
    static final int RETRYABLE_TOTAL_ATTEMPTS = 3;

    /**
     * Maximum allowed length of a {@code traceId}. Anything longer is rejected with a
     * friendly error (MCP-R-17).
     */
    static final int MAX_TRACE_ID_LENGTH = 128;

    /**
     * Maximum allowed length of a {@code fileName} (MCP-R-18).
     */
    static final int MAX_FILENAME_LENGTH = 256;

    /**
     * Maximum allowed length of a {@code keyword} filter (MCP-R-18).
     */
    static final int MAX_KEYWORD_LENGTH = 1024;

    /**
     * Pattern used to validate trace IDs. Matches the {@code IdUtil.fastSimpleUUID()-style}
     * values produced by the framework plus most user-supplied identifiers.
     */
    static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /**
     * ISO 8601 date / date-time pattern used to validate {@code startTime} / {@code endTime}.
     * Accepts {@code 2026-08-29}, {@code 2026-08-29T12:34:56Z}, {@code 2026-08-29T12:34:56.789+08:00}.
     */
    static final Pattern ISO_8601_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2}))?$");

    private final List<MethodTraceLogMcpProperties.HostInfo> hosts;
    private final RestClient fastClient;
    private final RestClient longClient;

    /**
     * Mutable stats about the most recent {@link #safeGet} / {@link #safePost} call. Updated
     * synchronously on the calling thread. Used by tests (MCP-R-19) to verify which RestClient
     * was used and how long the call took.
     */
    private volatile CallStats lastCall = CallStats.empty();

    /**
     * Backward-compatible constructor: a single {@link RestClient} is used for both fast and long
     * operations. Used by unit tests.
     */
    public MethodTraceLogMcpService(List<MethodTraceLogMcpProperties.HostInfo> hosts, RestClient client) {
        this(hosts, client, client);
    }

    /**
     * Production constructor: a fast {@link RestClient} (30s read) for normal tool calls and a long
     * {@link RestClient} (120s read) for {@code downloadLog} / {@code decompileMethod}.
     * <p>
     * Validation of {@code hosts} runs from {@link #validateHosts()} (a {@code @PostConstruct}
     * method), so callers that manually instantiate the class bypass it — call it themselves for
     * the same safety net.
     */
    public MethodTraceLogMcpService(List<MethodTraceLogMcpProperties.HostInfo> hosts,
                                    RestClient fastClient,
                                    RestClient longClient) {
        this.hosts = hosts;
        this.fastClient = fastClient;
        this.longClient = longClient;
    }

    /**
     * Tool operation classification. Used by {@link #safeGet} / {@link #safePost} to pick
     * the right {@link RestClient} and to decide whether to retry.
     */
    enum ToolOp {
        /** Idempotent GET on the fast client. Retries on failure. */
        FAST_RETRYABLE(false, true),
        /** Mutating or POST on the fast client. No retries. */
        FAST_NON_RETRYABLE(false, false),
        /** Slow but idempotent GET on the long client. Retries on failure. */
        LONG_RETRYABLE(true, true),
        /** Long-running POST or mutating on the long client. No retries. */
        LONG_NON_RETRYABLE(true, false);

        final boolean longTimeout;
        final boolean retryable;

        ToolOp(boolean longTimeout, boolean retryable) {
            this.longTimeout = longTimeout;
            this.retryable = retryable;
        }
    }

    /**
     * Snapshot of the most recent {@link #safeGet} / {@link #safePost} call. Used by tests
     * (MCP-R-19) and (informally) by the audit log on the failure path.
     */
    static final class CallStats {
        final String tool;
        final String host;
        final String path;
        final String status;
        final long durationMs;
        final boolean longClient;

        CallStats(String tool, String host, String path, String status, long durationMs, boolean longClient) {
            this.tool = tool;
            this.host = host;
            this.path = path;
            this.status = status;
            this.durationMs = durationMs;
            this.longClient = longClient;
        }

        static CallStats empty() {
            return new CallStats(null, null, null, null, 0L, false);
        }
    }

    /**
     * Validate every {@link MethodTraceLogMcpProperties.HostInfo} at startup.
     * <p>
     * Each host must have a non-blank {@code name}, a parseable {@code URL} whose scheme is
     * {@code http} or {@code https}, and the {@code name} must be unique across the list. Boot
     * fails with {@link IllegalStateException} on the first violation. (Round 15 / MCP-R-09)
     * additionally logs a WARN for every host whose {@code url} starts with {@code http://}
     * <em>and</em> whose {@code apiKey} is non-empty: sending a static API key in cleartext over
     * the wire is almost always a misconfiguration.
     */
    @PostConstruct
    public void validateHosts() {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalStateException(
                    "methodTraceLog MCP requires at least one host configured under method-trace-log.mcp.hosts; " +
                            "the list was " + (hosts == null ? "null" : "empty") + ".");
        }
        Map<String, Integer> seenNames = new HashMap<>();
        for (int i = 0; i < hosts.size(); i++) {
            MethodTraceLogMcpProperties.HostInfo h = hosts.get(i);
            String prefix = "method-trace-log.mcp.hosts[" + i + "]";
            if (h == null) {
                throw new IllegalStateException(prefix + " must not be null.");
            }
            if (h.getName() == null || h.getName().isBlank()) {
                throw new IllegalStateException(prefix + ".name must not be blank.");
            }
            String url = h.getUrl();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(prefix + ".url must not be blank.");
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(prefix + ".url is not a valid URI: '" + url + "'", e);
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalStateException(prefix + ".url scheme must be http or https, got: '" + scheme + "' (url=" + url + ").");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException(prefix + ".url must include a host component, got: '" + url + "'.");
            }
            Integer prev = seenNames.put(h.getName(), i);
            if (prev != null) {
                throw new IllegalStateException("Duplicate host name '" + h.getName() +
                        "' at method-trace-log.mcp.hosts[" + prev + "] and [" + i + "]; host names must be unique.");
            }
            // MCP-R-09: cleartext API key warning.
            if (scheme.equalsIgnoreCase("http") && notBlank(h.getApiKey())) {
                log.warn("[mcp-config] host '{}' (url={}) is configured with a non-empty apiKey over HTTP (cleartext). " +
                        "Use HTTPS or move apiKey to a secrets manager.", h.getName(), url);
            }
        }
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

    /**
     * Health probe. Tries {@code /actuator/health} first (the conventional Spring Boot Actuator
     * endpoint, returns JSON like {@code {"status":"UP"}}). Falls back to
     * {@code /methodTraceLog/view/callServices} as a smoke test for hosts that expose the
     * methodTraceLog surface but not the bare {@code /actuator}. (Round 15 / MCP-R-13)
     * <p>
     * If both endpoints return a non-2xx, the result is a structured error message describing
     * exactly which endpoints were tried — much clearer than the historical "404" on a host that
     * just happens not to expose {@code /actuator}.
     */
    @Tool(description = "健康检查：尝试访问主机的 actuator endpoints，验证网络与鉴权是否通畅")
    public String ping(@ToolParam(description = "主机名称（与配置中的 name 一致）") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";

        // 1) Try /actuator/health first.
        String health = safeGet("ping", host.get(), "/actuator/health", ToolOp.FAST_RETRYABLE);
        if (!health.contains("\"error\":") && !health.contains("NOT_FOUND")) {
            return health;
        }

        // 2) Fall back to /methodTraceLog/view/callServices.
        String cs = safeGet("ping", host.get(), "/methodTraceLog/view/callServices", ToolOp.FAST_RETRYABLE);
        if (!cs.contains("\"error\":") && !cs.contains("NOT_FOUND")) {
            return cs;
        }

        // 3) Both failed. Return a clear "host not exposing actuator" message.
        return "{\"error\":\"HOST_NOT_EXPOSING_ACTUATOR\",\"host\":\"" + hostName + "\"," +
                "\"tried\":[\"/actuator/health\",\"/methodTraceLog/view/callServices\"]," +
                "\"hint\":\"enable management.endpoints.web.exposure.include=methodtrace,health on the host\"}";
    }

    // ===================== 日志服务（CallService）维度 =====================

    @Tool(description = "列出主机上注册的全部方法调用日志输出服务及其启用状态")
    public String getCallServices(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return safeGet("getCallServices", host.get(), "/methodTraceLog/view/callServices", ToolOp.FAST_RETRYABLE);
    }

    @Tool(description = "启用或停用指定的日志服务（控制是否输出方法追踪日志）")
    public String setCallServiceEnable(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "服务名称（从 getCallServices 获取）") String serviceName,
            @ToolParam(description = "true=启用；false=停用") boolean enable) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return safeGet("setCallServiceEnable", host.get(),
                "/methodTraceLog/view/callService?name=" + urlEncode(serviceName) + "&enable=" + enable,
                ToolOp.FAST_NON_RETRYABLE);
    }

    // ===================== 方法追踪（trace）维度 =====================

    @Tool(description = "获取最近的方法调用追踪记录列表。可按类名/方法名过滤，只看错误，限制返回数量。")
    public String getMethodTraceList(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "可选：类名 substring 过滤（不区分大小写）", required = false) String className,
            @ToolParam(description = "可选：方法名 substring 过滤（不区分大小写）", required = false) String methodName,
            @ToolParam(description = "可选：true=只返回 AFTER_THROW 的 trace，默认 false", required = false) Boolean onlyErrors,
            @ToolParam(description = "可选：最多返回多少条，默认 200，最大 2000", required = false) Integer limit) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        // MCP-R-16: don't emit a trailing "?" when no filters are set.
        boolean anyFilter = className != null || methodName != null || onlyErrors != null || limit != null;
        if (!anyFilter) {
            return safeGet("getMethodTraceList", host.get(), "/methodTraceLog/view/list", ToolOp.FAST_RETRYABLE);
        }
        // 用 boolean[] 绕开 lambda 内的 effectively-final 限制
        boolean[] first = {true};
        StringBuilder sb = new StringBuilder("/methodTraceLog/view/list?");
        if (className != null) appendQuery(sb, first, "className", className);
        if (methodName != null) appendQuery(sb, first, "methodName", methodName);
        if (onlyErrors != null) appendQuery(sb, first, "onlyErrors", onlyErrors.toString());
        if (limit != null) appendQuery(sb, first, "limit", String.valueOf(Math.max(1, Math.min(2000, limit))));
        return safeGet("getMethodTraceList", host.get(), sb.toString(), ToolOp.FAST_RETRYABLE);
    }

    /**
     * 拼接 query string：首次不加 {@code &}，后续加。
     */
    private static void appendQuery(StringBuilder sb, boolean[] first, String key, String value) {
        if (!first[0]) sb.append('&');
        first[0] = false;
        sb.append(key).append('=').append(urlEncode(value));
    }

    @Tool(description = "根据 traceId 拉取完整的方法调用链（span 树）")
    public String getMethodTraceByTraceId(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "追踪 ID（来自一次方法调用的根节点）") String traceId) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        // MCP-R-17: validate traceId before forwarding.
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            return "{\"error\":\"INVALID_TRACE_ID\",\"traceIdLength\":" +
                    (traceId == null ? 0 : traceId.length()) +
                    ",\"hint\":\"traceId must match " + TRACE_ID_PATTERN.pattern() + "\"}";
        }
        return safeGet("getMethodTraceByTraceId", host.get(),
                "/methodTraceLog/view/traceid?id=" + urlEncode(traceId),
                ToolOp.FAST_RETRYABLE);
    }

    // ===================== 告警与慢方法维度 =====================

    @Tool(description = "获取主机近期触发的告警事件。返回按时间倒序。")
    public String getAlerts(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "可选：最多返回多少条，默认 50", required = false) Integer limit) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        int n = limit == null ? 50 : Math.max(1, Math.min(500, limit));
        return safeGet("getAlerts", host.get(), "/methodTraceLog/view/alerts?limit=" + n, ToolOp.FAST_RETRYABLE);
    }

    @Tool(description = "获取主机上调用最慢的方法 Top-N（基于 Micrometer Timer 直方图，包含 p50/p95/p99/max）。")
    public String getSlowMethods(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "可选：统计窗口（分钟），默认 5", required = false) Integer windowMinutes,
            @ToolParam(description = "可选：返回前 N 条，默认 10，最大 100", required = false) Integer topN) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        int w = windowMinutes == null ? 5 : Math.max(1, Math.min(60, windowMinutes));
        int n = topN == null ? 10 : Math.max(1, Math.min(100, topN));
        return safeGet("getSlowMethods", host.get(),
                "/methodTraceLog/view/slowMethods?windowMinutes=" + w + "&topN=" + n,
                ToolOp.FAST_RETRYABLE);
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
                .append(urlEncode(className)).append("&methodName=").append(urlEncode(methodName));
        if (timeoutSeconds != null) path.append("&timeoutSeconds=").append(timeoutSeconds);
        // Not in the retry list (per Round-14 risk inventory: idempotent GETs only).
        return safeGet("decompileMethod", host.get(), path.toString(), ToolOp.LONG_NON_RETRYABLE);
    }

    // ===================== 日志文件维度 =====================

    @Tool(description = "列出主机配置的日志目录下的日志文件")
    public String getLogFiles(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return safeGet("getLogFiles", host.get(), "/methodTraceLog/logFile/files", ToolOp.FAST_RETRYABLE);
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

        // MCP-R-18: body-size & time-format guards. Fail fast instead of round-tripping to the host.
        String argErr = validateLogQueryArgs(fileName, keyword, startTime, endTime);
        if (argErr != null) return argErr;

        // POST /methodTraceLog/logFile/query with JSON body
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileName", fileName);
        if (keyword != null) body.put("keyword", keyword);
        if (startTime != null) body.put("startTime", startTime);
        if (endTime != null) body.put("endTime", endTime);
        if (maxLines != null) body.put("maxLines", maxLines);
        if (level != null) body.put("level", level);

        return safePost("queryLogContent", host.get(), "/methodTraceLog/logFile/query", body, ToolOp.LONG_NON_RETRYABLE);
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

        // MCP-R-18: same guards as queryLogContent.
        String argErr = validateLogQueryArgs(fileName, keyword, startTime, endTime);
        if (argErr != null) return argErr;

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileName", fileName);
        if (keyword != null) body.put("keyword", keyword);
        if (startTime != null) body.put("startTime", startTime);
        if (endTime != null) body.put("endTime", endTime);
        if (level != null) body.put("level", level);
        if (maxLines != null) body.put("maxLines", maxLines);

        return safePost("downloadLog", host.get(), "/methodTraceLog/logFile/download", body, ToolOp.LONG_NON_RETRYABLE);
    }

    /**
     * Centralised validation for the log-query POST tools (Round 15 / MCP-R-18).
     *
     * @return {@code null} on success; an error-JSON string the caller should return verbatim
     *         when any input fails the cap or the ISO 8601 format.
     */
    static String validateLogQueryArgs(String fileName, String keyword, String startTime, String endTime) {
        if (fileName == null || fileName.isBlank()) {
            return "{\"error\":\"INVALID_FILE_NAME\",\"hint\":\"fileName must not be blank\"}";
        }
        if (fileName.length() > MAX_FILENAME_LENGTH) {
            return "{\"error\":\"FILE_NAME_TOO_LONG\",\"length\":" + fileName.length() +
                    ",\"max\":" + MAX_FILENAME_LENGTH + "}";
        }
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            return "{\"error\":\"KEYWORD_TOO_LONG\",\"length\":" + keyword.length() +
                    ",\"max\":" + MAX_KEYWORD_LENGTH + "}";
        }
        if (startTime != null && !ISO_8601_PATTERN.matcher(startTime).matches()) {
            return "{\"error\":\"INVALID_TIME_FORMAT\",\"field\":\"startTime\",\"value\":\"" +
                    startTime + "\"}";
        }
        if (endTime != null && !ISO_8601_PATTERN.matcher(endTime).matches()) {
            return "{\"error\":\"INVALID_TIME_FORMAT\",\"field\":\"endTime\",\"value\":\"" +
                    endTime + "\"}";
        }
        return null;
    }

    // ===================== 实时监控维度 =====================

    @Tool(description = "开始实时监控（tail）指定的日志文件。新增的行会通过 WebSocket 推送到前端页")
    public String startMonitor(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "要监控的日志文件名") String fileName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        // MCP-R-18: clamp fileName.
        if (fileName == null || fileName.isBlank()) {
            return "{\"error\":\"INVALID_FILE_NAME\",\"hint\":\"fileName must not be blank\"}";
        }
        if (fileName.length() > MAX_FILENAME_LENGTH) {
            return "{\"error\":\"FILE_NAME_TOO_LONG\",\"length\":" + fileName.length() +
                    ",\"max\":" + MAX_FILENAME_LENGTH + "}";
        }
        return safeGet("startMonitor", host.get(),
                "/methodTraceLog/logFile/monitor/start?fileName=" + urlEncode(fileName),
                ToolOp.FAST_NON_RETRYABLE);
    }

    @Tool(description = "停止实时监控指定的日志文件")
    public String stopMonitor(
            @ToolParam(description = "主机名称") String hostName,
            @ToolParam(description = "要停止监控的日志文件名") String fileName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        if (fileName == null || fileName.isBlank()) {
            return "{\"error\":\"INVALID_FILE_NAME\",\"hint\":\"fileName must not be blank\"}";
        }
        if (fileName.length() > MAX_FILENAME_LENGTH) {
            return "{\"error\":\"FILE_NAME_TOO_LONG\",\"length\":" + fileName.length() +
                    ",\"max\":" + MAX_FILENAME_LENGTH + "}";
        }
        return safeGet("stopMonitor", host.get(),
                "/methodTraceLog/logFile/monitor/stop?fileName=" + urlEncode(fileName),
                ToolOp.FAST_NON_RETRYABLE);
    }

    @Tool(description = "查看主机的实时日志监控状态（当前监控的文件、是否在监控、监控文件数）")
    public String getMonitorStatus(@ToolParam(description = "主机名称") String hostName) {
        Optional<MethodTraceLogMcpProperties.HostInfo> host = findHost(hostName);
        if (host.isEmpty()) return "主机不存在";
        return safeGet("getMonitorStatus", host.get(), "/methodTraceLog/logFile/monitor/status", ToolOp.FAST_RETRYABLE);
    }

    // ===================== 内部辅助 =====================

    private Optional<MethodTraceLogMcpProperties.HostInfo> findHost(String name) {
        if (hosts == null) return Optional.empty();
        return hosts.stream()
                .filter(h -> h.getName() != null && h.getName().equals(name))
                .findAny();
    }

    /**
     * Wrap a GET call with retry, timeout selection, audit logging, and structured error reporting.
     * (Round 15 / MCP-R-20) every invocation emits one structured audit line on {@code stderr}.
     */
    private String safeGet(String tool, MethodTraceLogMcpProperties.HostInfo host, String path, ToolOp op) {
        return doWithRetry(tool, host, path, op, () -> {
            RestClient client = op.longTimeout ? longClient : fastClient;
            return client.get()
                    .uri(host.getUrl() + path)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                    .headers(h -> { if (notBlank(host.getApiKey())) h.set("X-Api-Key", host.getApiKey()); })
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * Wrap a POST call with retry, timeout selection, audit logging, and structured error reporting.
     */
    private String safePost(String tool, MethodTraceLogMcpProperties.HostInfo host, String path, Object body, ToolOp op) {
        return doWithRetry(tool, host, path, op, () -> {
            RestClient client = op.longTimeout ? longClient : fastClient;
            return client.post()
                    .uri(host.getUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                    .headers(h -> { if (notBlank(host.getApiKey())) h.set("X-Api-Key", host.getApiKey()); })
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * Run an HTTP action with retries on retryable operations. Catches
     * {@link RestClientException}, classifies the failure, and returns a structured JSON
     * error string on final failure so the MCP transport does not propagate stack traces
     * to the LLM. Also emits one structured audit log line on every call (success or failure).
     */
    private String doWithRetry(String tool,
                                MethodTraceLogMcpProperties.HostInfo host,
                                String path,
                                ToolOp op,
                                java.util.function.Supplier<String> action) {
        long start = System.currentTimeMillis();
        int maxAttempts = op.retryable ? RETRYABLE_TOTAL_ATTEMPTS : 1;
        long backoffMs = RETRY_BACKOFF_INITIAL_MS;
        RestClientException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = action.get();
                long dur = System.currentTimeMillis() - start;
                lastCall = new CallStats(tool, host == null ? "" : host.getName(), path, "OK", dur, op.longTimeout);
                writeAudit(tool, host, path, "OK", dur);
                return result;
            } catch (RestClientException e) {
                last = e;
                log.warn("[mcp-rest] host={} op={} attempt={}/{} failed: {}",
                        host.getName(), op, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= RETRY_BACKOFF_MULTIPLIER;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        String code = classifyErrorCode(last);
        long dur = System.currentTimeMillis() - start;
        lastCall = new CallStats(tool, host == null ? "" : host.getName(), path, code, dur, op.longTimeout);
        writeAudit(tool, host, path, code, dur);
        return toErrorJson(host, last);
    }

    /**
     * Single-source-of-truth for the audit log line. Logback's {@code ConsoleAppender} for
     * {@code mcp.audit} routes to {@code System.err} only — see {@code logback-spring.xml}.
     */
    private static void writeAudit(String tool, MethodTraceLogMcpProperties.HostInfo host, String path, String status, long durationMs) {
        auditLog.info("tool={} host={} path={} status={} duration={}ms",
                nullSafe(tool),
                host == null ? "" : nullSafe(host.getName()),
                nullSafe(path),
                nullSafe(status),
                durationMs);
    }

    /** Visible for tests. Was the most recent safeGet / safePost routed through the long client? */
    public boolean wasLastCallLongClient() {
        return lastCall.longClient;
    }

    /** Visible for tests. Stats for the most recent safeGet / safePost. */
    public CallStats getLastCallStats() {
        return lastCall;
    }

    /**
     * Classify a {@link RestClientException} into a stable error code and turn it into a
     * JSON string the LLM can read. Detection priority:
     * <ol>
     *   <li>{@code RESPONSE_TOO_LARGE} — body exceeded {@code maxInMemorySize}, signalled
     *       by {@link org.springframework.web.client.support.RestClientContentException}
     *       or by message keywords ({@code maxinmemorysize}, {@code too large},
     *       {@code length}).</li>
     *   <li>{@code HOST_UNREACHABLE} — {@link ResourceAccessException} (timeouts,
     *       connection refused, DNS failures).</li>
     *   <li>{@code UNAUTHORIZED} — host returned 401.</li>
     *   <li>{@code NOT_FOUND} — host returned 404.</li>
     *   <li>{@code CLIENT_ERROR} — host returned 4xx.</li>
     *   <li>{@code HOST_ERROR} — host returned 5xx.</li>
     *   <li>{@code ERROR} — anything else.</li>
     * </ol>
     */
    static String toErrorJson(MethodTraceLogMcpProperties.HostInfo host, RestClientException e) {
        String code = classifyErrorCode(e);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", code);
        err.put("host", host == null ? "" : host.getName());
        if (e != null && e.getMessage() != null) err.put("cause", e.getMessage());
        try {
            return new ObjectMapper().writeValueAsString(err);
        } catch (JsonProcessingException jsonErr) {
            log.error("[mcp-rest] failed to serialize error JSON", jsonErr);
            return "{\"error\":\"INTERNAL\"}";
        }
    }

    static String classifyErrorCode(RestClientException e) {
        if (e == null) return "ERROR";
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // 1) Walk the cause chain looking for a SizeLimitingClientHttpRequestFactory.ResponseTooLargeException.
        //    Spring wraps request-factory IOExceptions in ResourceAccessException, so we cannot rely on the
        //    outer exception type alone.
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof SizeLimitingClientHttpRequestFactory.ResponseTooLargeException) {
                return "RESPONSE_TOO_LARGE";
            }
            t = t.getCause();
        }
        // 2) Message heuristics (catches unrelated code paths that wrap a size error differently).
        if (msg.contains("maxinmemorysize")
                || msg.contains("too large")
                || msg.contains("buffer exceeded")
                || msg.contains("content length")
                || msg.contains("exceeded limit")
                || msg.contains("exceeds limit")) {
            return "RESPONSE_TOO_LARGE";
        }
        if (e instanceof ResourceAccessException) {
            return "HOST_UNREACHABLE";
        }
        if (e instanceof HttpServerErrorException) {
            return "HOST_ERROR";
        }
        if (e instanceof HttpClientErrorException) {
            HttpClientErrorException ce = (HttpClientErrorException) e;
            if (ce.getStatusCode().value() == 401) return "UNAUTHORIZED";
            if (ce.getStatusCode().value() == 404) return "NOT_FOUND";
            return "CLIENT_ERROR";
        }
        return "ERROR";
    }

    /**
     * RFC 3986-style query value encoding. {@link java.net.URLEncoder} is form-encoding
     * (space {@code ->} {@code +}); for query/path string parameters we want
     * {@code space -> %20}. This helper is the single source of truth for query encoding.
     */
    static String urlEncode(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
