package cn.wubo.method.trace.log.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 {@code method-trace-log.file.log-path} 解析为绝对路径，并在目录不可写时让应用启动失败。
 * <p>
 * 在 {@link SpringApplication} 加载 application.yml / 解析 {@code @ConfigurationProperties} 之前运行，
 * 因此后续绑定 {@link cn.wubo.method.trace.log.MethodTraceLogProperties.FileProperties} 时拿到的就是绝对路径。
 * <p>
 * 路径解析使用 {@code user.dir}（来自 environment，若未设置则回退到 {@code System.getProperty("user.dir")}），
 * 避免直接调用 {@code new File(".").getAbsoluteFile()} 这种不经过 ENV 的写法。
 * <p>
 * 写回 environment：把解析后的绝对路径写入 {@code systemProperties}。在 {@code MutablePropertySources} 里，
 * {@code systemProperties} 是由 {@code StandardEnvironment} 构造函数第一个放入的属性源（index 0 = 最高优先级），
 * 因此 {@code Environment.getProperty(KEY)} 会优先读到解析后的绝对路径，无视该值来自 application.yml、
 * {@code SpringApplication.setDefaultProperties(...)} 还是命令行 {@code -D...}。
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
        // systemProperties 是 MutablePropertySources 中索引为 0 的属性源（最高优先级），
        // 写入即可让 Environment.getProperty(KEY) 始终返回解析后的绝对路径。
        env.getSystemProperties().put(KEY, path.toString());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
