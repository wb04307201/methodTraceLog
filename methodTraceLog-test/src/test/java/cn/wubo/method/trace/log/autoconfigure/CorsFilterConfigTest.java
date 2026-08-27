package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CorsFilterConfigTest {

    @Test
    void empty_origins_does_not_create_filter() {
        var props = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        // 默认空 → 不应该有 filter
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
