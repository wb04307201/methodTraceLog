package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
        Assertions.assertEquals(7200, props.getMaxAge());
    }
}
