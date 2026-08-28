package cn.wubo.method.trace.log.file;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // 摘掉测试期间挂的 appender
        if (testAppender != null && serviceLogger != null) {
            serviceLogger.detachAppender(testAppender);
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

    private ListAppender<ILoggingEvent> testAppender;
    private Logger serviceLogger;

    /**
     * 关闭日志监控时，{@link java.nio.file.WatchService#take()} 会抛 {@link java.nio.file.ClosedWatchServiceException}。
     * 该异常应当被静默吞掉（break 出循环），不能在 ERROR 级别打印任何日志。
     * 之前 bug：通用 Exception catch 块打 ERROR 并再调一次 close()，输出残留噪音。
     */
    @Test
    void destroy_shouldNotEmitErrorLogOnClosedWatchService() throws Exception {
        // 把 ListAppender 挂到 LogFileRealTimeService 的 logger 上
        serviceLogger = (Logger) LoggerFactory.getLogger(LogFileRealTimeService.class);
        testAppender = new ListAppender<>();
        testAppender.start();
        serviceLogger.addAppender(testAppender);
        // 确保该 logger 会把 ERROR 级别事件传给 appender
        Level prev = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.ALL);

        try {
            // 触发 destroy() → close() → watchService.close() → take() 抛 ClosedWatchServiceException
            service.destroy();
            // 给 watchFiles 线程一小段时间退出循环
            Thread.sleep(200);
        } finally {
            serviceLogger.setLevel(prev);
        }

        // 验证：没有 ERROR 级别的日志（特别是没有 "watch error" 之类）
        long errorCount = testAppender.list.stream()
                .filter(ev -> ev.getLevel() == Level.ERROR)
                .count();
        assertEquals(0, errorCount,
                "destroy() should not produce ERROR logs from ClosedWatchServiceException; got: "
                        + testAppender.list.stream()
                        .filter(ev -> ev.getLevel() == Level.ERROR)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList());
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