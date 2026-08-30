package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
 * <p>
 * <b>关于 URL pattern</b>：
 * 必须用 {@code FilterRegistrationBean} 显式指定 {@code /methodTraceLog/*}，
 * 否则 Spring Boot 会按 {@code /*} 全局注册，进而拦截用户自己业务路径
 * （例如 {@code /api/**}）的 preflight，导致业务 CorsFilter 失效、
 * 自定义路由被 401 / 500 拦截。这与 {@link LogConfig#methodTraceLogApiKeyFilter}
 * 的 URL pattern 策略一致。
 */
@Configuration
public class CorsFilterConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> methodTraceLogCorsFilter(MethodTraceLogProperties properties) {
        var cors = properties.getSecurity() != null && properties.getSecurity().getCors() != null
                ? properties.getSecurity().getCors()
                : null;
        CorsFilter filter;
        if (cors == null || cors.getAllowedOrigins() == null || cors.getAllowedOrigins().isEmpty()) {
            // 空配置：UrlBasedCorsConfigurationSource 上无匹配规则 → CorsFilter 透传 → 行为同未启用
            filter = new CorsFilter(new UrlBasedCorsConfigurationSource());
        } else {
            var cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(cors.getAllowedOrigins());
            cfg.setAllowedMethods(cors.getAllowedMethods());
            cfg.setAllowedHeaders(cors.getAllowedHeaders());
            cfg.setAllowCredentials(cors.isAllowCredentials());
            cfg.setMaxAge(cors.getMaxAge());

            var source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/methodTraceLog/**", cfg);
            filter = new CorsFilter(source);
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        // 与 methodTraceLogApiKeyFilter 一致：只覆盖 starter 自有命名空间，
        // 不要越界拦截业务路由。ApiKeyFilter.PATH_PREFIX = "/methodTraceLog/"
        registration.addUrlPatterns(ApiKeyFilter.PATH_PREFIX + "*");
        // 排在 API Key 之后：CORS preflight 在 ApiKeyFilter 中已经放行（OPTIONS 短路），
        // 业务请求先过 ApiKeyFilter 鉴权，再过 CORS 处理响应头，顺序与 401 行为保持一致。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("methodTraceLogCorsFilter");
        return registration;
    }
}
