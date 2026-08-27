package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class LogPathEnvironmentPostProcessorTest {

    @Configuration
    static class TestConfig {
    }

    @Test
    void relative_path_resolves_to_absolute() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.file.log-path", "./logs");
        // Avoid bringing up LogConfig / LogFileConfig which require a web environment
        props.put("spring.autoconfigure.exclude",
                "cn.wubo.method.trace.log.autoconfigure.LogConfig,"
                        + "cn.wubo.method.trace.log.autoconfigure.LogFileConfig");
        app.setDefaultProperties(props);
        try (ConfigurableApplicationContext ctx = app.run("--server.port=0")) {
            String resolved = ctx.getEnvironment().getProperty("method-trace-log.file.log-path");
            Assertions.assertNotNull(resolved, "log-path should be present after env post-processing");
            Assertions.assertTrue(Path.of(resolved).isAbsolute(),
                    "log-path should be resolved to an absolute path but was: " + resolved);
            Assertions.assertTrue(Files.isDirectory(Path.of(resolved)),
                    "log-path should exist as a directory but was: " + resolved);
        }
    }

    @Test
    void invalid_path_fails_fast() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        // Z: 驱动器在常规开发机几乎不存在；Files.createDirectories 会抛 IOException
        props.put("method-trace-log.file.log-path", "Z:\\this-drive-does-not-exist\\logs");
        props.put("spring.autoconfigure.exclude",
                "cn.wubo.method.trace.log.autoconfigure.LogConfig,"
                        + "cn.wubo.method.trace.log.autoconfigure.LogFileConfig");
        app.setDefaultProperties(props);
        Assertions.assertThrows(Exception.class, () -> app.run("--server.port=0"));
    }
}
