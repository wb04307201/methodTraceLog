package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.CallServiceStrategy;
import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.impl.log.SimpleLogServiceImpl;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.NamedContributors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.log.enable:true}")
@EnableConfigurationProperties(MethodTraceLogProperties.class)
public class LogConfig {

    @Bean
    public SimpleMonitorServiceImpl simpleMonitorService(MeterRegistry meterRegistry) {
        return new SimpleMonitorServiceImpl(meterRegistry);
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
        return new CallServiceStrategy(callServices,properties);
    }

    @Bean
    public LogAspect logAspect(CallServiceStrategy callServiceStrategy) {
        return new LogAspect(callServiceStrategy);
    }

    @Bean("wb04307201MethodTraceLogRouter")
    public RouterFunction<ServerResponse> methodTraceLogRouter(CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService, NamedContributors namedContributors) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/view", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/view.html"))));
        builder.GET("/methodTraceLog/view/callServices", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.getCallServices()));
        builder.GET("/methodTraceLog/view/callService", request -> {
            String name = request.param("name").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
            Boolean enable = Boolean.valueOf(request.param("enable").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "enable is required")));
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.setCallServiceEnable(name,enable));
        }
        );
        builder.GET("/methodTraceLog/view/list", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getMethodTraceInfos()));
        builder.GET("/methodTraceLog/view/traceid", request -> {
                    String id = request.param("id").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getByTraceId(id));
                }
        );
        return builder.build();
    }

}
