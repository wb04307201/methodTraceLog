package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@link LogFileRealTimeService} 多文件监控支持。
 * <p>
 * 修复前：{@code currentMonitorFile} 单字段，start 文件 B 会覆盖文件 A；
 * {@code stopMonitoring(name)} 不管参数都清空所有状态。
 * <p>
 * 修复后：内部用 {@code Map<String, MonitoredFile> monitoredFiles}，
 * 多个文件可同时被监控；停止一个不影响其它。
 */
class LogFileRealTimeServiceMultiFileTest {

    private LogFileRealTimeService service;
    private Path tempDir;
    private File file1;
    private File file2;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("mtl-realtime-multifile");
        file1 = tempDir.resolve("app1.log").toFile();
        file2 = tempDir.resolve("app2.log").toFile();
        try (FileWriter w = new FileWriter(file1)) {
            w.write("init1\n");
        }
        try (FileWriter w = new FileWriter(file2)) {
            w.write("init2\n");
        }

        MethodTraceLogProperties.FileProperties properties = new MethodTraceLogProperties.FileProperties();
        properties.setLogPath(tempDir.toString());

        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new LogFileRealTimeService(properties, messagingTemplate);
        service.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.destroy();
        }
        if (tempDir != null && Files.exists(tempDir)) {
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
    @DisplayName("start 2 个文件：两者都在 monitoredFiles 内")
    void start_2_files_then_both_active() throws Exception {
        Map<String, Object> r1 = service.startMonitoring("app1.log");
        Map<String, Object> r2 = service.startMonitoring("app2.log");
        assertEquals("monitor_started", r1.get("type"));
        assertEquals("monitor_started", r2.get("type"));

        Map<String, Object> status = service.getMonitorStatus();
        assertEquals(true, status.get("monitoring"));
        Object files = status.get("monitoredFiles");
        assertTrue(files instanceof Set, "monitoredFiles should be Set<String>");
        assertEquals(Set.of("app1.log", "app2.log"), files);
        assertEquals(2, status.get("monitoredFilesCount"));
    }

    @Test
    @DisplayName("stop 一个文件：另一个仍被监控")
    void stop_one_keeps_other() throws Exception {
        service.startMonitoring("app1.log");
        service.startMonitoring("app2.log");

        Map<String, Object> stopResult = service.stopMonitoring("app1.log");
        assertEquals("monitor_stopped", stopResult.get("type"));
        assertEquals("app1.log", stopResult.get("fileName"));

        Map<String, Object> status = service.getMonitorStatus();
        // 仍处于监控状态（还有 app2.log）
        assertEquals(true, status.get("monitoring"));
        assertEquals(Set.of("app2.log"), status.get("monitoredFiles"));
        assertEquals(1, status.get("monitoredFilesCount"));
    }

    @Test
    @DisplayName("stop 未启动的文件名：返回 monitor_not_started，不抛异常")
    void stop_unknown_filename_no_op() throws Exception {
        // 没有任何文件被启动
        Map<String, Object> r = service.stopMonitoring("never-started.log");
        assertNotNull(r);
        assertEquals("monitor_not_started", r.get("type"));
        assertEquals("never-started.log", r.get("fileName"));

        // 状态应当不变（仍是 0）
        Map<String, Object> status = service.getMonitorStatus();
        assertEquals(false, status.get("monitoring"));
        assertEquals(Set.of(), status.get("monitoredFiles"));
        assertEquals(0, status.get("monitoredFilesCount"));
    }

    @Test
    @DisplayName("重复 start 同一文件：返回 monitor_already_started，不重复启动")
    void start_same_file_twice_returns_already_started() throws Exception {
        Map<String, Object> r1 = service.startMonitoring("app1.log");
        assertEquals("monitor_started", r1.get("type"));

        Map<String, Object> r2 = service.startMonitoring("app1.log");
        assertEquals("monitor_already_started", r2.get("type"));

        // 状态：只有 app1.log 一份
        Map<String, Object> status = service.getMonitorStatus();
        assertEquals(Set.of("app1.log"), status.get("monitoredFiles"));
        assertEquals(1, status.get("monitoredFilesCount"));
    }

    @Test
    @DisplayName("stop null/空：返回 monitor_not_started，不抛异常")
    void stop_null_or_empty_no_throw() throws Exception {
        // 先启动一个确保 stop(null) 不会清空所有（修复前 bug）
        service.startMonitoring("app1.log");

        Map<String, Object> rNull = service.stopMonitoring(null);
        assertEquals("monitor_not_started", rNull.get("type"));

        Map<String, Object> rEmpty = service.stopMonitoring("");
        assertEquals("monitor_not_started", rEmpty.get("type"));

        // app1.log 仍然在监控中
        Map<String, Object> status = service.getMonitorStatus();
        assertFalse(status.get("monitoredFiles") instanceof String,
                "monitoredFiles should not have been corrupted");
        assertEquals(Set.of("app1.log"), status.get("monitoredFiles"),
                "stop(null/empty) must not affect other monitored files");
    }
}
