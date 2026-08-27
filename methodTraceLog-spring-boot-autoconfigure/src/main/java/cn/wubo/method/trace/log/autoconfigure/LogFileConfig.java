package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.LogFileRealTimeService;
import cn.wubo.method.trace.log.file.LogFileService;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.file.dto.LogQueryRequestValidator;
import cn.wubo.method.trace.log.utils.ValidationUtils;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static cn.wubo.method.trace.log.file.Constants.ERROR;
import static cn.wubo.method.trace.log.file.Constants.MESSAGE;
import static org.springframework.web.servlet.function.RequestPredicates.accept;

@AutoConfiguration
@EnableWebSocketMessageBroker
@ConditionalOnExpression("${method-trace-log.file.enable:true}")
@EnableConfigurationProperties(MethodTraceLogProperties.class)
@Slf4j
public class LogFileConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 配置消息代理注册表
     *
     * @param config 消息代理注册表配置对象，用于设置消息代理相关参数
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 启用简单消息代理，指定/topic作为消息代理的前缀
        config.enableSimpleBroker("/topic");
        // 设置应用程序目的地前缀为/app
        config.setApplicationDestinationPrefixes("/app");
    }


    /**
     * 注册STOMP协议的端点，用于处理WebSocket连接请求
     *
     * @param registry STOMP端点注册器，用于配置和注册WebSocket端点
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 配置WebSocket端点，允许所有来源的跨域请求，并启用SockJS支持
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Bean
    public LogFileService logFileService(MethodTraceLogProperties properties) {
        return new LogFileService(properties.getFile());
    }

    @Bean
    public LogFileRealTimeService logFileRealTimeService(MethodTraceLogProperties properties, SimpMessagingTemplate messagingTemplate) {
        return new LogFileRealTimeService(properties.getFile(), messagingTemplate);
    }

    @Bean("wb04307201MethodTraceLogFileRouter")
    public RouterFunction<ServerResponse> methodTraceLogFileRouter(LogFileService fileService, LogFileRealTimeService logFileRealTimeService, Validator validator) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/logFile/files", accept(MediaType.APPLICATION_JSON), request -> ServerResponse.ok().body(fileService.getLogFiles()));
        builder.POST("/methodTraceLog/logFile/query", accept(MediaType.APPLICATION_JSON), request -> {
            try {
                LogQueryRequest logQueryRequest = request.body(LogQueryRequest.class);
                ValidationUtils.validate(validator, logQueryRequest);
                LogQueryRequestValidator.validate(logQueryRequest);
                return ServerResponse.ok().body(fileService.queryLogs(logQueryRequest));
            } catch (ConstraintViolationException e) {
                // 字段校验失败(fileName 为空等)→ 400 + 真实原因
                return ServerResponse.badRequest().body(Map.of(ERROR, "validation_failed", MESSAGE, e.getMessage()));
            } catch (IllegalArgumentException e) {
                // 文件不存在 / 路径非法 / 扩展名不允许 / 时间顺序非法 → 400 + 真实原因
                return ServerResponse.badRequest().body(Map.of(ERROR, "bad_request", MESSAGE, e.getMessage()));
            } catch (Exception e) {
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(ERROR, "internal_error", MESSAGE, e.getMessage()));
            }
        });
        builder.POST("/methodTraceLog/logFile/download", request -> {
            try {
                LogQueryRequest logQueryRequest = request.body(LogQueryRequest.class);
                ValidationUtils.validate(validator, logQueryRequest);
                LogQueryRequestValidator.validate(logQueryRequest);
                return ServerResponse.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition", "attachment;filename=" +  URLEncoder.encode(logQueryRequest.getFileName(), StandardCharsets.UTF_8)).build((req, res) -> {
                    try (PrintWriter writer = res.getWriter()) {
                        for (String line : fileService.downloadLog(logQueryRequest)) {
                            writer.println(line);
                        }
                    }
                    return null;
                });
            } catch (ConstraintViolationException e) {
                return ServerResponse.badRequest().body(Map.of(ERROR, "validation_failed", MESSAGE, e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ServerResponse.badRequest().body(Map.of(ERROR, "bad_request", MESSAGE, e.getMessage()));
            }
        });
        // REST 端点：start/stop/status。和现有 STOMP /app/start-monitor 等并存，互不干扰。
        builder.GET("/methodTraceLog/logFile/monitor/start", request -> {
            String fileName = request.param("fileName").orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "fileName is required"));
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(logFileRealTimeService.startMonitoring(fileName));
        });
        builder.GET("/methodTraceLog/logFile/monitor/stop", request -> {
            String fileName = request.param("fileName").orElse("");
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(logFileRealTimeService.stopMonitoring(fileName));
        });
        builder.GET("/methodTraceLog/logFile/monitor/status", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(logFileRealTimeService.getMonitorStatus()));
        RouterFunction<ServerResponse> built = builder.build();
        return built.filter(this::handleErrors);
    }

    /**
     * 与 {@link LogConfig#handleErrors} 对齐的兜底映射。
     * <p>
     * LogFileConfig 已对 /logFile/query 和 /logFile/download 显式 try/catch 输出 JSON 错误体，
     * 但 monitor/start 等端点没有；这里给整条 router 套一层兜底，保证响应格式与 LogConfig 一致。
     */
    ServerResponse handleErrors(ServerRequest req, HandlerFunction<ServerResponse> next) throws Exception {
        try {
            return next.handle(req);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, iae.getMessage(), iae);
        } catch (Exception e) {
            log.error("methodTraceLog file router error: {} {}", req.method(), req.uri(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "internal_error: " + e.getClass().getSimpleName(), e);
        }
    }

    @Controller
    public class LogWebSocketController {

        private final LogFileRealTimeService logFileRealTimeService;

        @Autowired
        public LogWebSocketController(LogFileRealTimeService logFileRealTimeService) {
            this.logFileRealTimeService = logFileRealTimeService;
        }

        /**
         * 开始监控日志文件
         *
         * @param message 包含文件名的消息
         * @return 响应消息
         */
        @MessageMapping("/start-monitor")
        @SendTo("/topic/log-monitor")
        public Map<String, Object> startMonitor(Map<String, String> message) {
            try {
                String fileName = message.get("fileName");
                if (fileName == null || fileName.trim().isEmpty()) {
                    return Map.of("type", ERROR, MESSAGE, "文件名不能为空");
                }

                return logFileRealTimeService.startMonitoring(fileName);
            } catch (Exception e) {
                return Map.of("type", ERROR, MESSAGE, "开始监控失败: " + e.getMessage());
            }
        }

        /**
         * 停止监控日志文件
         *
         * @param message 消息
         * @return 响应消息
         */
        @MessageMapping("/stop-monitor")
        @SendTo("/topic/log-monitor")
        public Map<String, Object> stopMonitor(Map<String, String> message) {
            try {
                return logFileRealTimeService.stopMonitoring(message.get("fileName"));
            } catch (Exception e) {
                return Map.of("type", ERROR, MESSAGE, "停止监控失败: " + e.getMessage());
            }
        }

        /**
         * 获取监控状态
         *
         * @param message 消息
         * @return 监控状态
         */
        @MessageMapping("/monitor-status")
        @SendTo("/topic/log-monitor")
        public Map<String, Object> getMonitorStatus(Map<String, String> message) {
            try {
                return logFileRealTimeService.getMonitorStatus();
            } catch (Exception e) {
                return Map.of("type", ERROR, MESSAGE, "获取监控状态失败: " + e.getMessage());
            }
        }

        /**
         * 心跳检测
         *
         * @param message 心跳消息
         * @return 心跳响应
         */
        @MessageMapping("/heartbeat")
        @SendTo("/topic/log-monitor")
        public Map<String, Object> heartbeat(Map<String, String> message) {
            return Map.of("type", "heartbeat", "timestamp", System.currentTimeMillis(), MESSAGE, "pong");
        }
    }

}
