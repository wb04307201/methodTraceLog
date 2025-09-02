package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.impl.invalid.InvalidServiceImpl;
import cn.wubo.method.trace.log.impl.log.SimpleLogServiceImpl;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

import static cn.wubo.method.trace.log.Constants.*;

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.log.enable:true}")
public class LogConfig {

    @Bean
    @ConditionalOnExpression("${method-trace-log.log.monitor:false}")
    public SimpleMonitorServiceImpl simpleMonitorService(MeterRegistry meterRegistry) {
        return new SimpleMonitorServiceImpl(meterRegistry);
    }

    @Bean
    @ConditionalOnExpression("${method-trace-log.log.monitor:false}")
    public MethodTraceLogEndPoint methodTraceLogEndPoint(MeterRegistry meterRegistry) {
        return new MethodTraceLogEndPoint(meterRegistry);
    }

    @Bean
    @ConditionalOnExpression("${method-trace-log.log.log:true}")
    public SimpleLogServiceImpl simpleLogService() {
        return new SimpleLogServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public InvalidServiceImpl invalidService() {
        return new InvalidServiceImpl();
    }

    /**
     * 创建日志切面Bean
     *
     * @param callServices 调用服务列表，用于日志切面处理
     * @return LogAspect 日志切面实例
     */
    @Bean
    public LogAspect logAspect(List<ICallService> callServices) {
        return new LogAspect(callServices);
    }

    @Bean("wb04307201MethodTraceLogRouter")
    @ConditionalOnExpression("${method-trace-log.log.monitor:false}")
    public RouterFunction<ServerResponse> methodTraceLogRouter(SimpleMonitorServiceImpl simpleMonitorService) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/log/monitor/view", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/monitor.html"))));
        builder.GET("/log/monitor/view/list", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getMethodTraceInfos()));
        builder.GET("/log/monitor/view/traceid", request -> {
                    String id = request.param("id").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required")).toString();
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getByTraceId(id));
                }
        );
        return builder.build();
    }

}
