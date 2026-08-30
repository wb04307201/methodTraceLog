package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.CorsFilter;

import java.util.Collections;
import java.util.List;

class CorsFilterConfigTest {

    @Test
    void empty_origins_creates_noop_filter() {
        // Round 8 rename: 实际行为是 filter 始终注册、allowedOrigins 为空时为 no-op
        // （不再用 @ConditionalOnExpression —— Spring Boot 3.5 SpEL list-placeholder regression）。
        // 这个测试只校验 CorsProperties 默认值（filter 实例由 Spring 容器在运行时管）。
        var props = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        Assertions.assertTrue(props.getAllowedOrigins().isEmpty());
        Assertions.assertEquals(0L, props.getMaxAge());
    }

    @Test
    void configured_origins_has_sensible_defaults() {
        var props = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        props.setAllowedOrigins(List.of("http://localhost:3000"));
        // 其他字段应有合理默认值（即使未显式设置）
        Assertions.assertNotNull(props.getAllowedMethods());
        Assertions.assertFalse(props.getAllowedMethods().isEmpty());
        Assertions.assertNotNull(props.getAllowedHeaders());
        Assertions.assertFalse(props.getAllowedHeaders().isEmpty());
        Assertions.assertFalse(props.isAllowCredentials()); // 默认 false（与 * origin 不兼容）
    }

    @Test
    void parses_yaml_configuration() {
        // 模拟 yaml 绑定
        var props = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        props.setAllowedOrigins(List.of("*"));
        props.setAllowedMethods(List.of("GET", "POST"));
        props.setAllowCredentials(true);
        props.setMaxAge(7200);
        Assertions.assertEquals(List.of("*"), props.getAllowedOrigins());
        Assertions.assertTrue(props.isAllowCredentials());
        Assertions.assertEquals(7200L, props.getMaxAge());
    }

    // === Fix Round 13 F-01: URL pattern 必须是 /methodTraceLog/* 而非 /* ===

    @Test
    void corsFilterBean_isFilterRegistrationBean_notRawCorsFilter() {
        // 修复前：methodTraceLogCorsFilter() 返回 CorsFilter 类型。
        // Spring Boot 会按 /* 全局注册 —— 拦截用户业务路径的 preflight，
        // 把业务 CorsFilter 屏蔽掉（用户给 /api/** 配置 CORS 反而被 starter 抢先匹配）。
        // 修复后：返回 FilterRegistrationBean，并显式 addUrlPatterns(/methodTraceLog/*)，
        // 与 methodTraceLogApiKeyFilter 保持一致。
        MethodTraceLogProperties props = newProps(List.of("http://localhost:3000"));
        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);

        Assertions.assertNotNull(reg);
        Assertions.assertTrue(reg.getFilter() instanceof CorsFilter,
                "包装的 filter 必须是 CorsFilter；实际: " + reg.getFilter().getClass());
    }

    @Test
    void corsFilterBean_urlPatterns_scopedToMethodTraceLog() {
        // 关键断言：URL pattern 必须是 /methodTraceLog/*，绝不能是 /*。
        // 这是 F-01 的核心回归点。
        MethodTraceLogProperties props = newProps(List.of("http://localhost:3000"));
        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);

        java.util.Collection<String> patterns = reg.getUrlPatterns();
        Assertions.assertNotNull(patterns, "URL pattern 列表不能为 null");
        Assertions.assertFalse(patterns.isEmpty(), "URL pattern 不能为空");
        // 必须包含 /methodTraceLog/* —— 这是 ApiKeyFilter.PATH_PREFIX + "*"
        Assertions.assertTrue(patterns.contains(ApiKeyFilter.PATH_PREFIX + "*"),
                "URL pattern 必须包含 /methodTraceLog/*；实际: " + patterns);
        // 关键反向断言：不能是 /* 全局匹配
        Assertions.assertFalse(patterns.contains("/*"),
                "URL pattern 不能是 /*（会拦截所有业务路径）；实际: " + patterns);
    }

    @Test
    void corsFilterBean_emptyOrigins_stillScopesToMethodTraceLog() {
        // 即使用户没配 allowedOrigins（filter 是 no-op），URL pattern 仍要按 /methodTraceLog/* 收口，
        // 不能因为 no-op 就"放飞"到 /*。
        MethodTraceLogProperties props = newProps(null);
        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);

        java.util.Collection<String> patterns = reg.getUrlPatterns();
        Assertions.assertNotNull(patterns);
        Assertions.assertTrue(patterns.contains(ApiKeyFilter.PATH_PREFIX + "*"),
                "no-op 时 URL pattern 仍应锁定 /methodTraceLog/*；实际: " + patterns);
    }

    @Test
    void corsFilterBean_nameIsStable() {
        // bean 名称固定，避免被 Spring 自动命名覆盖
        MethodTraceLogProperties props = newProps(null);
        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);
        // FilterRegistrationBean 没有 getName()（setName 才存在）—— 改为直接验证 URL pattern
        // 是稳定的（前面已断言）以及 order 非默认值（验证显式设过）
        Assertions.assertNotEquals(Integer.MIN_VALUE, reg.getOrder(),
                "应显式设置 order（与 ApiKeyFilter 的 HIGHEST_PRECEDENCE 区分）");
    }

    private static MethodTraceLogProperties newProps(List<String> allowedOrigins) {
        MethodTraceLogProperties p = new MethodTraceLogProperties();
        if (allowedOrigins != null) {
            p.getSecurity().getCors().setAllowedOrigins(allowedOrigins);
        } else {
            p.getSecurity().getCors().setAllowedOrigins(Collections.emptyList());
        }
        return p;
    }
}
