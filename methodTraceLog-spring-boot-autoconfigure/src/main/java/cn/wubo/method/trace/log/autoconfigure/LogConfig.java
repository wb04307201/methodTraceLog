package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.impl.invalid.InvalidServiceImpl;
import cn.wubo.method.trace.log.impl.log.SimpleLogServiceImpl;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.List;

import static cn.wubo.method.trace.log.Constants.*;

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.log.enable:true}")
public class LogConfig {

    @Bean
    @ConditionalOnExpression("${method-trace-log.log.monitor:false}")
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            registry.counter(METHOD_CALLS_TOTAL);
            registry.counter(METHOD_CALLS_SUCCESS);
            registry.counter(METHOD_CALLS_FAILURE);
            registry.timer(METHOD_EXECUTION_TIME);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${method-trace-log.log.monitor:false}")
    public ICallService simpleMonitorService(MeterRegistry meterRegistry) {
        return new SimpleMonitorServiceImpl(meterRegistry);
    }

    @Bean
    @ConditionalOnExpression("${method-trace-log.log.log:true}")
    public ICallService simpleLogService() {
        return new SimpleLogServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public ICallService invalidService() {
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

}
