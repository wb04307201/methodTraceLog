package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.autoconfigure.LogFileConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-86: {@code method-trace-log.file.enable=false} 时，{@link LogFileConfig} 整体不注册。
 * <p>
 * 当前实现：
 * <pre>{@code
 * @ConditionalOnExpression("${method-trace-log.file.enable:true}")
 * }</pre>
 * 配在 {@link LogFileConfig} 类级别上 —— 即整个 {@code @AutoConfiguration} 类失效，
 * 其下注册的 {@code LogFileService} / {@code LogFileRealTimeService} / file router 等
 * 都不会被 Spring 拉到。
 * <p>
 * 本测试验证：当 {@code method-trace-log.file.enable=false} 时，{@code LogFileService}
 * bean 不在上下文里。
 */
class LogFileConfigDisabledTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    ValidationAutoConfiguration.class,
                    LogFileConfig.class));

    @Test
    void fileServiceBean_absent_when_disabled() {
        runner.withPropertyValues(
                        "method-trace-log.file.enable=false",
                        "method-trace-log.log.enable=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LogFileService.class));
    }

    @Test
    void fileServiceBean_present_when_enabled() {
        runner.withPropertyValues(
                        "method-trace-log.file.enable=true",
                        "method-trace-log.log.enable=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(LogFileService.class));
    }

    @Test
    void fileService_defaultEnabled() {
        // 不显式配置 file.enable 时默认 true（兼容现有部署）
        runner.withPropertyValues(
                        "method-trace-log.log.enable=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(LogFileService.class));
    }

    @Test
    void fileRouterBean_absent_when_disabled() {
        runner.withPropertyValues(
                        "method-trace-log.file.enable=false",
                        "method-trace-log.log.enable=true")
                .run(ctx -> assertThat(ctx)
                        .doesNotHaveBean("wb04307201MethodTraceLogFileRouter"));
    }
}