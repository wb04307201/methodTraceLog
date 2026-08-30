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

    /**
     * 修复 F-02 的回归测试：之前用 {@code addFirst} 把默认值放在最高优先级，
     * 用户在 application.yml 设置的值反而被覆盖。改用 {@code addLast} 后，
     * 用户值（在 {@code application.yml} 解析来的 PropertySource 中，优先级高于
     * {@code addLast} 的 MapPropertySource）必须能覆盖默认。
     * <p>
     * 与 {@link #does_not_override_user_values} 的区别：本测试用
     * {@code --server.error.include-message=never} 命令行参数（最常见生产环境
     * 覆盖方式）而不是 defaultProperties，模拟真实运维场景。
     */
    @Test
    void user_value_via_commandline_arg_overrides_default() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        props.put("spring.autoconfigure.exclude",
                "cn.wubo.method.trace.log.autoconfigure.LogConfig,"
                        + "cn.wubo.method.trace.log.autoconfigure.LogFileConfig");
        app.setDefaultProperties(props);
        // 命令行参数走 CommandLinePropertySource（在 defaultProperties 之后才生效，但优先级高于
        // mtl-error-message-defaults 的 addLast）—— 关键：addFirst 错误实现会让 mtl 默认胜出。
        try (ConfigurableApplicationContext ctx = app.run(
                "--server.port=0",
                "--server.error.include-message=never")) {
            Assertions.assertEquals("never",
                    ctx.getEnvironment().getProperty("server.error.include-message"),
                    "用户命令行 --server.error.include-message=never 必须覆盖 starter 默认 'always'");
        }
    }
}
