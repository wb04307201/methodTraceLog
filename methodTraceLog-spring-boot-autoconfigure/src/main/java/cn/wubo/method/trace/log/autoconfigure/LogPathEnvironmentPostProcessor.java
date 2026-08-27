package cn.wubo.method.trace.log.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 把 {@code method-trace-log.file.log-path} 解析为绝对路径，并在目录不可写时让应用启动失败。
 * <p>
 * 在 {@link SpringApplication} 加载 application.yml / 解析 {@code @ConfigurationProperties} 之前运行，
 * 因此后续绑定 {@link cn.wubo.method.trace.log.MethodTraceLogProperties.FileProperties} 时拿到的就是绝对路径。
 * <p>
 * 路径解析使用 {@code user.dir}（来自 environment，若未设置则回退到 {@code System.getProperty("user.dir")}），
 * 避免直接调用 {@code new File(".").getAbsoluteFile()} 这种不经过 ENV 的写法。
 * <p>
 * 写回 environment 时，会同时更新 {@code systemProperties} 与 {@code defaultProperties}：
 * 前者是默认回退位置（命令行 {@code -D...} / 系统属性），后者覆盖通过 {@code SpringApplication.setDefaultProperties(...)}
 * 设置的属性（它的优先级高于 systemProperties）。
 */
public class LogPathEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String KEY = "method-trace-log.file.log-path";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String raw = env.getProperty(KEY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            String userDir = env.getProperty("user.dir", System.getProperty("user.dir"));
            path = Path.of(userDir).resolve(raw).normalize();
        }
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "method-trace-log.file.log-path 不可写: " + path + " (" + e.getMessage() + ")", e);
        }
        String resolved = path.toString();
        // 默认回退位置（systemProperties 永远存在）
        env.getSystemProperties().put(KEY, resolved);
        // 显式覆盖 setDefaultProperties(...) 注入的属性源 —— 它优先级高于 systemProperties，
        // 不更新的话 Environment.getProperty(KEY) 仍会读到原始的相对路径。
        PropertySource<?> defaultProps = env.getPropertySources().get("defaultProperties");
        if (defaultProps instanceof MapPropertySource mps) {
            Object source = mps.getSource();
            if (source instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).put(KEY, resolved);
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
