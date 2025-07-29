package cn.wubo.log.config;

import cn.wubo.log.core.DefaultLogServiceImpl;
import cn.wubo.log.core.ILogService;
import cn.wubo.log.core.LogAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties({LogProperties.class})
public class LogConfig {

    @Bean
    @ConditionalOnMissingBean
    public ILogService logService() {
        return new DefaultLogServiceImpl();
    }


    /**
     * 创建日志切面 Bean，用于处理日志记录逻辑
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
