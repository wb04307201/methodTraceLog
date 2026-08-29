package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 过滤器：把用户在 {@code application.yml} 里的
 * {@code method-trace-log.security.cors.*} 配置转成 {@link CorsFilter}，
 * 匹配 {@code /methodTraceLog/**}，让面板（部署在不同 origin）能正常调用 API。
 * <p>
 * <b>关于无 opt-in 条件</b>：
 * 早期版本用 {@code @ConditionalOnExpression("!'${...cors.allowed-origins:}'.isEmpty()")}，
 * 但实测在 surefire fork JVM 下，该条件拿到的是 scalar=null / arr=null
 * （{@code Environment.getProperty(...)} 解析列表占位符在 PARSE_CONFIGURATION 与
 * REGISTER_BEAN 阶段都拿不到值），导致 bean 永远不会被创建、OPTIONS preflight 一律返回 403。
 * 改用始终注册的写法：若 {@code allowedOrigins} 为空，{@link CorsFilter} 在
 * {@code UrlBasedCorsConfigurationSource} 上找不到配置，preflight 请求会落到
 * Spring DispatcherServlet（由 Spring MVC 自行判断），与历史行为一致 —— 关闭 CORS 时
 * 不会引入任何新行为，开启 CORS 时立即生效。
 */
@Configuration
public class CorsFilterConfig {

    @Bean
    public CorsFilter methodTraceLogCorsFilter(MethodTraceLogProperties properties) {
        var cors = properties.getSecurity() != null && properties.getSecurity().getCors() != null
                ? properties.getSecurity().getCors()
                : null;
        if (cors == null || cors.getAllowedOrigins() == null || cors.getAllowedOrigins().isEmpty()) {
            // 空配置：UrlBasedCorsConfigurationSource 上无匹配规则 → CorsFilter 透传 → 行为同未启用
            return new CorsFilter(new UrlBasedCorsConfigurationSource());
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
