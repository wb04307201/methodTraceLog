package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 验证 {@link ErrorMessagePropertiesPostProcessor}：
 *  1. 当用户没设置 {@code server.error.include-message} / {@code include-stacktrace} 时，
 *     注入默认值（always / never）。
 *  2. 当用户已经设置时，不覆盖用户值。
 */
class ErrorMessagePropertiesPostProcessorTest {

    @Configuration
    static class TestConfig {
    }

    @Test
    void adds_defaults_when_unset() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        // Avoid bringing up LogConfig / LogFileConfig which require a web environment
        props.put("spring.autoconfigure.exclude",
                "cn.wubo.method.trace.log.autoconfigure.LogConfig,"
                        + "cn.wubo.method.trace.log.autoconfigure.LogFileConfig");
        app.setDefaultProperties(props);
        try (ConfigurableApplicationContext ctx = app.run("--server.port=0")) {
            Assertions.assertEquals("always",
                    ctx.getEnvironment().getProperty("server.error.include-message"),
                    "include-message default should be 'always' when user hasn't set it");
            Assertions.assertEquals("never",
                    ctx.getEnvironment().getProperty("server.error.include-stacktrace"),
                    "include-stacktrace default should be 'never' when user hasn't set it");
        }
    }

    @Test
    void does_not_override_user_values() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        props.put("server.error.include-message", "never");
        props.put("server.error.include-stacktrace", "always");
        props.put("spring.autoconfigure.exclude",
                "cn.wubo.method.trace.log.autoconfigure.LogConfig,"
                        + "cn.wubo.method.trace.log.autoconfigure.LogFileConfig");
        app.setDefaultProperties(props);
        try (ConfigurableApplicationContext ctx = app.run("--server.port=0")) {
            Assertions.assertEquals("never",
                    ctx.getEnvironment().getProperty("server.error.include-message"),
                    "include-message must respect user-set value");
            Assertions.assertEquals("always",
                    ctx.getEnvironment().getProperty("server.error.include-stacktrace"),
                    "include-stacktrace must respect user-set value");
        }
    }
}
