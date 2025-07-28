package cn.wubo.log.config;

import cn.wubo.log.core.ILogService;
import cn.wubo.log.core.LogAspect;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.lang.reflect.InvocationTargetException;

@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties({LogProperties.class})
public class LogConfig {

    @Bean
    public ILogService logService(LogProperties properties) {
        String className = properties.getLogService();
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("logService 类名不能为空");
        }

        try {
            Class<?> clazz = Class.forName(className);
            if (!ILogService.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("类 " + className + " 未实现 ILogService 接口");
            }

            return (ILogService) clazz.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到指定的 logService 类: " + className, e);
        } catch (InstantiationException e) {
            throw new RuntimeException("无法实例化 logService 类: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("无法访问 logService 类的构造函数: " + className, e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("logService 类缺少无参构造函数: " + className, e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("logService 类构造函数执行异常: " + className, e);
        }
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
