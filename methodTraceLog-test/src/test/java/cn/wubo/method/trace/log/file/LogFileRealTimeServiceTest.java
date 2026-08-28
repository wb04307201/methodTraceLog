package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 验证 stopMonitoring() 后 getMonitorStatus() 的 monitoredFiles 计数被正确清零。
 *
 * <p>修复前，stopMonitoring() 只重置 {@code monitoring} 标志位与 {@code currentMonitorFile}，
 * 但不清空内部 {@code filePositions}，导致 status 报告
 * {@code monitoring=false} 与 {@code monitoredFiles=1} 同时出现，状态不一致。
 */
class LogFileRealTimeServiceTest {

    private LogFileRealTimeService service;
    private Path tempDir;
    private File logFile;

    @BeforeEach
    void setUp() throws Exception {
        // 创建临时日志目录与一个真实存在的日志文件，供 startMonitoring() 使用
        tempDir = Files.createTempDirectory("mtl-realtime-test");
        logFile = tempDir.resolve("app.log").toFile();
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("initial line\n");
        }

        MethodTraceLogProperties.FileProperties properties = new MethodTraceLogProperties.FileProperties();
        properties.setLogPath(tempDir.toString());

        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

        service = new LogFileRealTimeService(properties, messagingTemplate);
        // 触发 watchService / executorService 初始化
        service.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 关闭线程池与 watchService，避免拖到下一个测试
        if (service != null) {
            service.destroy();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            // 递归删除临时目录
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void stopMonitoring_shouldResetMonitoredFilesCounter() throws Exception {
        // 1) 启动监控 → monitoredFiles = 1, monitoring = true
        Map<String, Object> startResult = service.startMonitoring("app.log");
        assertNotNull(startResult);
        assertEquals("monitor_started", startResult.get("type"));

        Map<String, Object> statusAfterStart = service.getMonitorStatus();
        assertEquals(true, statusAfterStart.get("monitoring"));
        assertEquals("app.log", statusAfterStart.get("currentFile"));
        assertEquals(1, statusAfterStart.get("monitoredFiles"));

        // 2) 停止监控 → monitoredFiles 必须回到 0，且 monitoring = false
        Map<String, Object> stopResult = service.stopMonitoring("app.log");
        assertNotNull(stopResult);
        assertEquals("monitor_stopped", stopResult.get("type"));

        Map<String, Object> statusAfterStop = service.getMonitorStatus();
        assertFalse((Boolean) statusAfterStop.get("monitoring"),
                "monitoring flag should be false after stopMonitoring()");
        assertEquals("", statusAfterStop.get("currentFile"));
        assertEquals(0, statusAfterStop.get("monitoredFiles"),
                "monitoredFiles counter must reset to 0 on monitor_stopped");
    }
}