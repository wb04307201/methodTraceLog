package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.service.ILogService;
import cn.wubo.method.trace.log.service.impl.DefaultLogServiceImpl;
import cn.wubo.method.trace.log.monitor.MonitorLogServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static cn.wubo.method.trace.log.monitor.Constants.*;

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.enable:true}")
public class LogConfig {

    @Bean
    @ConditionalOnExpression("${method-trace-log.monitor:false}")
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
    @ConditionalOnExpression("${method-trace-log.monitor:false}")
    public ILogService monitorLogService(MeterRegistry meterRegistry) {
        return new MonitorLogServiceImpl(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ILogService defaultLogService() {
        return new DefaultLogServiceImpl();
    }


    /**
     * 创建日志切面 Bean，用于处理日志记录逻辑
     *
     * @param logService 日志服务接口实现，用于执行具体的日志记录操作
     * @return LogAspect 日志切面实例，用于拦截方法执行并记录日志
     * @throws IllegalArgumentException 当logService参数为null时抛出
     */
    @Bean
    public LogAspect logAspect(ILogService logService) {
        // 参数校验，防止 logService 为 null
        if (logService == null) {
            throw new IllegalArgumentException("ILogService 不能为 null");
        }

        // 创建并返回 LogAspect 实例
        return new LogAspect(logService);
    }

}
