package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R-88: {@code method-trace-log.file.log-path} 未配置 / 空白时，
 * {@link LogPathEnvironmentPostProcessor} 必须短路返回（不创建目录、不修改 env），
 * 让后续 {@code FileProperties.logPath = "./logs"}（默认值）生效。
 * <p>
 * 关键路径：
 * <pre>{@code
 *   String raw = env.getProperty(KEY);
 *   if (raw == null || raw.isBlank()) {
 *     return;  // 不创建、不写回
 *   }
 * }</pre>
 */
class LogPathBlankDefaultTest {

    @Test
    void null_logPath_isShortCircuit() {
        LogPathEnvironmentPostProcessor processor = new LogPathEnvironmentPostProcessor();
        MockEnvironment env = new MockEnvironment();
        // 不设 log-path

        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, null));

        // env 不应有 log-path（短路不写回）
        assertNull(env.getProperty("method-trace-log.file.log-path"),
                "null 路径时 postProcessor 必须短路返回，不写入 env");
    }

    @Test
    void blank_logPath_isShortCircuit() {
        LogPathEnvironmentPostProcessor processor = new LogPathEnvironmentPostProcessor();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("method-trace-log.file.log-path", "   ");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, null));
        // 空白仍是 env 内的值（短路不写回）
        assertEquals("   ", env.getProperty("method-trace-log.file.log-path"),
                "空白路径短路：env 内的值不被覆盖");
    }

    @Test
    void empty_logPath_isShortCircuit() {
        LogPathEnvironmentPostProcessor processor = new LogPathEnvironmentPostProcessor();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("method-trace-log.file.log-path", "");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, null));
        // 空字符串短路
        assertEquals("", env.getProperty("method-trace-log.file.log-path"),
                "空字符串路径短路");
    }

    @Test
    void default_logPath_isDotLogs() {
        // 锁住默认值契约：FileProperties.logPath = "./logs"
        cn.wubo.method.trace.log.MethodTraceLogProperties.FileProperties props =
                new cn.wubo.method.trace.log.MethodTraceLogProperties.FileProperties();
        assertEquals("./logs", props.getLogPath(),
                "FileProperties.logPath 默认必须是 \"./logs\"");
    }

    @Test
    void relative_logPath_getsResolvedToAbsolute() {
        // 设置相对路径 → postProcessor 应当 resolve 成绝对路径写入 systemProperties
        // （MockEnvironment 的 mock property source 优先级高于 systemProperties，
        // 所以 env.getProperty() 仍返回 mock 里的 "./logs"；验证 systemProperties
        // 里的写入才是关键。）
        LogPathEnvironmentPostProcessor processor = new LogPathEnvironmentPostProcessor();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("method-trace-log.file.log-path", "./logs");
        env.setProperty("user.dir", System.getProperty("java.io.tmpdir"));

        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, null));

        // systemProperties 里写入的应当是绝对路径
        Object resolved = env.getSystemProperties().get("method-trace-log.file.log-path");
        org.junit.jupiter.api.Assertions.assertNotNull(resolved,
                "postProcessor 必须把绝对路径写入 systemProperties");
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Path.of(resolved.toString()).isAbsolute(),
                "相对路径应被解析成绝对路径；实际: " + resolved);
    }
}