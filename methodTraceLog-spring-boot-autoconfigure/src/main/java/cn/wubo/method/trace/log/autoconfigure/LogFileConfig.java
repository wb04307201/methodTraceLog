package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.LogFileRealTimeService;
import cn.wubo.method.trace.log.file.LogFileService;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.utils.ValidationUtils;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
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
    public RouterFunction<ServerResponse> methodTraceLogFileRouter(LogFileService fileService, Validator validator) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/logFile", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/logFile.html"))));
        builder.GET("/methodTraceLog/logFile/files", accept(MediaType.APPLICATION_JSON), request -> ServerResponse.ok().body(fileService.getLogFiles()));
        builder.POST("/methodTraceLog/logFile/query", accept(MediaType.APPLICATION_JSON), request -> {
            LogQueryRequest logQueryRequest = request.body(LogQueryRequest.class);
            ValidationUtils.validate(validator, logQueryRequest);
            return ServerResponse.ok().body(fileService.queryLogs(logQueryRequest));
        });
        builder.POST("/methodTraceLog/logFile/download", request -> {
            LogQueryRequest logQueryRequest = request.body(LogQueryRequest.class);
            ValidationUtils.validate(validator, logQueryRequest);
            return ServerResponse.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition", "attachment;filename=" +  URLEncoder.encode(logQueryRequest.getFileName(), StandardCharsets.UTF_8)).build((req, res) -> {
                try (PrintWriter writer = res.getWriter()) {
                    for (String line : fileService.downloadLog(logQueryRequest)) {
                        writer.println(line);
                    }
                }
                return null;
            });
        });
        return builder.build();
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
