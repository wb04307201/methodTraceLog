package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.CallServiceStrategy;
import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.impl.log.SimpleLogServiceImpl;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import cn.wubo.method.trace.log.sampler.Sampler;
import cn.wubo.method.trace.log.security.MtlSessionService;
import cn.wubo.method.trace.log.store.FileTraceStore;
import cn.wubo.method.trace.log.store.ITraceStore;
import cn.wubo.method.trace.log.store.InMemoryTraceStore;
import cn.wubo.method.trace.log.store.NoOpTraceStore;
import cn.wubo.method.trace.log.utils.DecompilerUtils;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.log.enable:true}")
@EnableConfigurationProperties(MethodTraceLogProperties.class)
public class LogConfig {

    @Bean
    public MtlSessionService mtlSessionService(MethodTraceLogProperties properties) {
        long ttl = properties.getSecurity() != null && properties.getSecurity().getSession() != null
                ? properties.getSecurity().getSession().getTtlMillis()
                : 8L * 60 * 60 * 1000L;
        return new MtlSessionService(ttl);
    }

    @Bean
    public Sampler mtlSampler(MethodTraceLogProperties properties) {
        double rate = properties.getLog() == null || properties.getLog().getSampleRate() == null
                ? 1.0
                : properties.getLog().getSampleRate();
        return new HeadBasedSampler(rate);
    }

    /**
     * 根据 method-trace-log.log.trace-store.type 选择存储实现。
     *  - in-memory（默认）：ConcurrentHashMap + CopyOnWriteArrayList
     *  - file            ：每条根 trace 落盘为 JSON
     *  - none            ：NoOp（仅 Micrometer 指标）
     */
    @Bean
    public ITraceStore mtlTraceStore(MethodTraceLogProperties properties) {
        MethodTraceLogProperties.LogProperties log = properties.getLog();
        if (log == null || log.getTraceStore() == null) {
            return new InMemoryTraceStore();
        }
        MethodTraceLogProperties.TraceStoreProperties ts = log.getTraceStore();
        String type = ts.getType() == null ? "in-memory" : ts.getType().toLowerCase();
        return switch (type) {
            case "file" -> new FileTraceStore(
                    ts.getPath(),
                    ts.getTtlMillis(),
                    ts.getMaxTraces(),
                    ts.isRebuildIndexOnStart());
            case "none" -> NoOpTraceStore.INSTANCE;
            default -> new InMemoryTraceStore();
        };
    }

    @Bean
    public SimpleMonitorServiceImpl simpleMonitorService(MeterRegistry meterRegistry, ITraceStore traceStore, MethodTraceLogProperties properties) {
        long maxAge = properties.getLog() != null && properties.getLog().getTraceStore() != null
                ? properties.getLog().getTraceStore().getTtlMillis()
                : 8L * 60 * 60 * 1000L;
        return new SimpleMonitorServiceImpl(meterRegistry, traceStore, maxAge);
    }

    @Bean
    public MethodTraceLogEndPoint methodTraceLogEndPoint(MeterRegistry meterRegistry) {
        return new MethodTraceLogEndPoint(meterRegistry);
    }

    @Bean
    public SimpleLogServiceImpl simpleLogService() {
        return new SimpleLogServiceImpl();
    }

    @Bean
    public CallServiceStrategy callServiceStrategy(List<ICallService> callServices, MethodTraceLogProperties properties) {
        return new CallServiceStrategy(callServices, properties);
    }

    @Bean
    public LogAspect logAspect(CallServiceStrategy callServiceStrategy, Sampler sampler) {
        return new LogAspect(callServiceStrategy, sampler);
    }

    /**
     * 注册 /methodTraceLog/** 的 API Key 鉴权过滤器。
     * <p>
     * 仅在 /methodTraceLog/ 命名空间下生效；其它业务 URL 完全不受影响。
     * 若 security.apiKey 未配置，则 filter 内部自动放行（仅开发环境用）。
     * 鉴权方式：X-Api-Key header 或 MTRACE_SESSION cookie，任一命中即放行。
     */
    @Bean
    public FilterRegistrationBean<ApiKeyFilter> methodTraceLogApiKeyFilter(MethodTraceLogProperties properties, MtlSessionService sessionService) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyFilter(properties, sessionService));
        registration.addUrlPatterns(ApiKeyFilter.PATH_PREFIX + "*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("methodTraceLogApiKeyFilter");
        return registration;
    }

    /**
     * Trace context 入口过滤器：解析请求中的 traceparent 头注入 MDC，让上下游 trace 串起来。
     * 优先级比 API Key filter 低（让它在 API key 之后跑，避免给无授权请求解析 trace）。
     */
    @Bean
    public FilterRegistrationBean<TraceContextFilter> traceContextFilter(MethodTraceLogProperties properties) {
        if (properties.getPropagate() == null || !properties.getPropagate().isHttpInbound()) {
            return null;
        }
        FilterRegistrationBean<TraceContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceContextFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("methodTraceLogTraceContextFilter");
        return registration;
    }

    /**
     * RestClient 自定义器：自动给所有出站 HTTP 请求注入 traceparent 头。
     */
    @Bean
    public TraceContextRestClientCustomizer traceContextRestClientCustomizer(MethodTraceLogProperties properties) {
        if (properties.getPropagate() == null || !properties.getPropagate().isRestClientOutbound()) {
            return null;
        }
        return new TraceContextRestClientCustomizer();
    }

    /**
     * RestTemplate 拦截器：用户主动设置到自己的 RestTemplate 上。
     */
    @Bean
    public TraceContextRestTemplateInterceptor traceContextRestTemplateInterceptor(MethodTraceLogProperties properties) {
        if (properties.getPropagate() == null || !properties.getPropagate().isRestTemplateInterceptor()) {
            return null;
        }
        return new TraceContextRestTemplateInterceptor();
    }

    @Bean("wb04307201MethodTraceLogRouter")
    public RouterFunction<ServerResponse> methodTraceLogRouter(CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService, MethodTraceLogProperties properties, MtlSessionService sessionService) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/panel", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/panel.html"))));
        commonRouter(builder, callServiceStrategy, simpleMonitorService);
        authRouter(builder, properties, sessionService);
        decompileRouter(builder, properties);
        return builder.build();
    }

    /**
     * 鉴权端点：登录、注销、状态查询。这三个端点本身在 ApiKeyFilter 的白名单内，无需 cookie。
     */
    private void authRouter(RouterFunctions.Builder builder, MethodTraceLogProperties properties, MtlSessionService sessionService) {
        long ttlSeconds = Math.max(60L, (properties.getSecurity() != null && properties.getSecurity().getSession() != null
                ? properties.getSecurity().getSession().getTtlMillis()
                : 8L * 60 * 60 * 1000L) / 1000L);

        // POST /methodTraceLog/login  body: {"apiKey":"..."}  → 200 + Set-Cookie
        builder.POST("/methodTraceLog/login", request -> {
            String configuredKey = properties.getSecurity() == null ? "" : properties.getSecurity().getApiKey();
            if (configuredKey == null || configuredKey.isEmpty()) {
                // 未配置 apiKey：直接返回 200 + 假 cookie（让前端误以为已登录）
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("status", "ok", "message", "auth disabled"));
            }
            // 优先 X-Api-Key header（mtlAuth.js 走这个路径），其次请求体中的 "X-Api-Key" 字段
            String provided = request.headers().firstHeader("X-Api-Key");
            if (provided == null) {
                try {
                    String ct = request.servletRequest().getContentType();
                    if (ct != null && ct.toLowerCase().contains("json")) {
                        byte[] body = new byte[Math.max(1, (int) request.servletRequest().getContentLengthLong() + 1)];
                        int read = request.servletRequest().getInputStream().read(body);
                        if (read > 0) {
                            String s = new String(body, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim();
                            // 简单 grep：避免引 Jackson
                            if (s.contains(":")) {
                                int idx = s.indexOf(':');
                                int start = s.indexOf('"', idx);
                                int end = s.indexOf('"', start + 1);
                                if (start > 0 && end > start) {
                                    provided = s.substring(start + 1, end);
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (provided == null || !provided.equals(configuredKey)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid apiKey");
            }
            String sid = sessionService.create();
            Cookie cookie = new Cookie(MtlSessionService.COOKIE_NAME, sid);
            cookie.setHttpOnly(true);
            cookie.setPath("/methodTraceLog/");
            cookie.setMaxAge((int) Math.min(Integer.MAX_VALUE, ttlSeconds));
            return ServerResponse.ok()
                    .cookie(cookie)
                    .header("Set-Cookie", String.format("%s=%s; Path=/methodTraceLog/; Max-Age=%d; HttpOnly; SameSite=Lax",
                            MtlSessionService.COOKIE_NAME, sid, ttlSeconds))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "ok", "ttlSeconds", ttlSeconds));
        });

        // POST /methodTraceLog/logout  → 200 + 清 cookie
        builder.POST("/methodTraceLog/logout", request -> {
            String sid = null;
            if (request.servletRequest().getCookies() != null) {
                for (var c : request.servletRequest().getCookies()) {
                    if (MtlSessionService.COOKIE_NAME.equals(c.getName())) {
                        sid = c.getValue();
                        break;
                    }
                }
            }
            if (sid != null) {
                sessionService.invalidate(sid);
            }
            return ServerResponse.ok()
                    .header("Set-Cookie", String.format("%s=; Path=/methodTraceLog/; Max-Age=0; HttpOnly; SameSite=Lax",
                            MtlSessionService.COOKIE_NAME))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "ok"));
        });

        // GET /methodTraceLog/session/status  → 200 总是返回（用于前端判断是否需要登录）
        builder.GET("/methodTraceLog/session/status", request -> {
            String configuredKey = properties.getSecurity() == null ? "" : properties.getSecurity().getApiKey();
            boolean authEnabled = configuredKey != null && !configuredKey.isEmpty();
            // 这个端点本身免密，但要看 cookie 是否有效
            boolean sessionValid = false;
            if (request.servletRequest().getCookies() != null) {
                for (var c : request.servletRequest().getCookies()) {
                    if (MtlSessionService.COOKIE_NAME.equals(c.getName()) && sessionService.validate(c.getValue())) {
                        sessionValid = true;
                        break;
                    }
                }
            }
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of(
                    "authEnabled", authEnabled,
                    "sessionValid", sessionValid));
        });
    }

    private void commonRouter(RouterFunctions.Builder builder, CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService) {
        builder.GET("/methodTraceLog/view/callServices", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.getCallServices()));
        builder.GET("/methodTraceLog/view/callService", request -> {
                    String name = request.param("name").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
                    Boolean enable = Boolean.valueOf(request.param("enable").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "enable is required")));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.setCallServiceEnable(name, enable));
                }
        );
        // /view/list 支持查询参数：className、methodName、onlyErrors、limit
        builder.GET("/methodTraceLog/view/list", request -> {
            String cn = request.param("className").orElse(null);
            String mn = request.param("methodName").orElse(null);
            boolean onlyErrors = Boolean.parseBoolean(request.param("onlyErrors").orElse("false"));
            int limit;
            try {
                limit = Math.max(1, Math.min(2000, Integer.parseInt(request.param("limit").orElse("200"))));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be a number");
            }
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getMethodTraceInfos(cn, mn, onlyErrors, limit));
        });
        builder.GET("/methodTraceLog/view/traceid", request -> {
                    String id = request.param("id").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getByTraceId(id));
                }
        );
        // /view/export?format=json|csv  导出最近根 trace 列表
        builder.GET("/methodTraceLog/view/export", request -> {
            String format = request.param("format").orElse("json").toLowerCase();
            String cn = request.param("className").orElse(null);
            String mn = request.param("methodName").orElse(null);
            boolean onlyErrors = Boolean.parseBoolean(request.param("onlyErrors").orElse("false"));
            int limit = Math.max(1, Math.min(5000, parseIntSafe(request.param("limit").orElse("1000"), 1000)));
            var data = simpleMonitorService.getMethodTraceInfos(cn, mn, onlyErrors, limit);
            if ("csv".equals(format)) {
                String csv = toCsv(data);
                return ServerResponse.ok()
                        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                        .header("Content-Disposition", "attachment; filename=\"method-traces.csv\"")
                        .body(csv);
            }
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(data);
        });

    }

    private static int parseIntSafe(String s, int dflt) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String toCsv(java.util.List<cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo> data) {
        StringBuilder sb = new StringBuilder("traceid,className,methodName,startMillis,durationMs,status,errorMessage\n");
        for (var info : data) {
            if (info == null || info.getBefore() == null) continue;
            var b = info.getBefore();
            long start = b.getTimeMillis() == null ? 0 : b.getTimeMillis();
            long dur = (info.getAfter() != null && info.getAfter().getTimeMillis() != null) ? info.getAfter().getTimeMillis() - start : -1;
            String status = info.getAfter() == null ? "RUNNING" : info.getAfter().getLogActionEnum().name();
            String err = "";
            if (info.getAfter() != null && info.getAfter().getContext() != null) {
                String c = String.valueOf(info.getAfter().getContext());
                err = c.length() > 200 ? c.substring(0, 200) : c;
                err = err.replace("\n", " ").replace(",", ";");
            }
            sb.append(csvField(b.getTraceid()))
                    .append(',').append(csvField(b.getClassName()))
                    .append(',').append(csvField(b.getMethodName()))
                    .append(',').append(start)
                    .append(',').append(dur)
                    .append(',').append(csvField(status))
                    .append(',').append(csvField(err))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String csvField(String s) {
        if (s == null) return "";
        if (s.contains("\"") || s.contains(",")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 反编译端点。
     * <p>
     * GET /methodTraceLog/decompile?className=foo.Bar&methodName=baz&timeoutSeconds=10
     * <p>
     * 返回 String 文本（plain/text），内容为去掉注解后的 Java 源码。
     * 异常会由 RouterFunction 框架包装为 4xx/5xx + 简单 message body。
     */
    private void decompileRouter(RouterFunctions.Builder builder, MethodTraceLogProperties properties) {
        long defaultTimeout = properties.getDecompile() == null ? 10L : Math.max(1L, properties.getDecompile().getTimeoutSeconds());
        builder.GET("/methodTraceLog/decompile", request -> {
            String className = request.param("className")
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "className is required"));
            String methodName = request.param("methodName")
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "methodName is required"));
            long timeout = request.param("timeoutSeconds")
                    .map(s -> {
                        try {
                            return Math.max(1L, Long.parseLong(s));
                        } catch (NumberFormatException e) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timeoutSeconds must be a number");
                        }
                    })
                    .orElse(defaultTimeout);
            String src = DecompilerUtils.decompile(className, methodName, timeout);
            return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body(DecompilerUtils.removeAnnotations(src));
        });
    }

}
