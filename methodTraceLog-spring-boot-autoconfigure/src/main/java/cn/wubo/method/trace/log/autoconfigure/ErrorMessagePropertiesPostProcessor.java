package cn.wubo.method.trace.log.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code server.error.include-message=always} 与 {@code server.error.include-stacktrace=never}
 * 设为 starter 的默认行为，仅在用户未显式配置时生效。
 * <p>
 * 背景：{@link cn.wubo.method.trace.log.autoconfigure.LogConfig} 的
 * {@code handleErrors} 把 4xx/5xx 转成 {@code ResponseStatusException(reason)} 抛出。
 * Spring Boot 3.x 的 {@code DefaultErrorAttributes} 默认 {@code include-message=never}，
 * 导致 {@code reason} 被丢弃，前端只看到通用 404 / 500 JSON 体。把 include-message 改为
 * {@code always} 后，{@code "message": "Method not found: ..."} 会出现在 body 中。
 * <p>
 * include-stacktrace 显式设为 {@code never}，避免把内部堆栈泄露到响应里（安全考虑）。
 * <p>
 * 用 {@code EnvironmentPostProcessor} + {@code MapPropertySource.addLast}：作为低优先级默认，
 * 用户在 application.yml / 环境变量 / 命令行里设置的值（更高优先级的 PropertySource）会自动覆盖。
 * <p>
 * 关键修复：之前用 {@code addFirst} 把默认值放在最高优先级，导致用户在 application.yml
 * 里设置的值反而被覆盖。Spring 的 {@code StandardEnvironment} 把 {@code application.yml}
 * 等"用户源"放在 {@code MapPropertySource} 之前（更高优先级），所以"用户源"覆盖"默认源"
 * 必须是：默认源 {@code addLast}（最低优先级）。{@code addFirst} 会让默认源挤掉用户源。
 */
public class ErrorMessagePropertiesPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (env.getProperty("server.error.include-message") == null) {
            defaults.put("server.error.include-message", "always");
        }
        if (env.getProperty("server.error.include-stacktrace") == null) {
            defaults.put("server.error.include-stacktrace", "never");
        }
        if (!defaults.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource(
                    "mtl-error-message-defaults", defaults));
        }
    }
}
