package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 过滤器：仅当 {@code method-trace-log.security.cors.allowed-origins} 非空时注册。
 * <p>
 * 把用户在 {@code application.yml} 里的 CORS 配置转成 {@link CorsFilter}，
 * 匹配 {@code /methodTraceLog/**}，让面板（部署在不同 origin）能正常调用 API。
 * <p>
 * 表达式里把属性值包成字符串再 {@code .isEmpty()}：Spring 绑定逗号分隔列表时类型是
 * {@code List<String>}，不能直接调用 {@code .isEmpty()}（会抛 SpEL 类型错误）；
 * 改为单引号包成字符串后再判断，无论属性是否定义（默认 {@code :""}）都安全。
 */
@Configuration
@ConditionalOnExpression("!'${method-trace-log.security.cors.allowed-origins:}'.isEmpty()")
public class CorsFilterConfig {

    @Bean
    public CorsFilter methodTraceLogCorsFilter(MethodTraceLogProperties properties) {
        var cors = properties.getSecurity() != null && properties.getSecurity().getCors() != null
                ? properties.getSecurity().getCors()
                : null;
        if (cors == null) {
            return new CorsFilter(new UrlBasedCorsConfigurationSource()); // 空配置兜底
        }
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(cors.getAllowedOrigins());
        cfg.setAllowedMethods(cors.getAllowedMethods());
        cfg.setAllowedHeaders(cors.getAllowedHeaders());
        cfg.setAllowCredentials(cors.isAllowCredentials());
        cfg.setMaxAge(cors.getMaxAge());

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/methodTraceLog/**", cfg);
        return new CorsFilter(source);
    }
}
