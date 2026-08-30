package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.LogFileRealTimeService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.mock;

/**
 * LogFileRealTimeService 关闭时序与线程清理测试。
 * <p>
 * 验证：
 *  <ul>
 *      <li>startMonitoring → destroy() 链路下，watchFiles / 调度线程在合理时间内退出</li>
 *      <li>destroy() 第二次调用不会抛 NPE（idempotent）</li>
 *      <li>关闭后 getMonitorStatus 仍然可读（monitoredFiles=空），不抛异常</li>
 *      <li>没有非 daemon 的 mtl-log-realtime 残留线程</li>
 *  </ul>
 * <p>
 * 注意：WatchService 在 Windows 上的事件投递可能延迟/丢失，因此本测试只断言关闭路径
 * 与线程清理，不强求 verify(STOMP) 已被调用 —— 那一条由现有的
 * {@code LogFileRealTimeServiceMultiFileTest} 用直接驱动方式覆盖。
 */
class LogFileRealTimeServiceShutdownIT {

    @Test
    void destroy_isClean_andThreadsExitPromptly(@TempDir Path tempDir) throws Exception {
        // 准备：临时日志目录 + 真实日志文件
        Path logFile = tempDir.resolve("app.log");
        try (FileWriter w = new FileWriter(logFile.toFile())) {
            w.write("seed\n");
        }

        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(tempDir.toString());
        fp.setAllowedExtensions(java.util.List.of(".log"));

        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

        LogFileRealTimeService svc = new LogFileRealTimeService(fp, messagingTemplate);
        svc.afterPropertiesSet();

        // 启动监控
        svc.startMonitoring("app.log");
        Assertions.assertEquals(1, svc.getMonitorStatus().get("monitoredFilesCount"));

        // 追加一些行（不一定能触发 WatchService 事件，但保证文件处于活跃状态）
        try (FileWriter w = new FileWriter(logFile.toFile(), true)) {
            for (int i = 0; i < 100; i++) {
                w.write("event-" + i + "\n");
            }
        }
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // destroy 第一次
        long start = System.currentTimeMillis();
        svc.destroy();
        long elapsed = System.currentTimeMillis() - start;
        Assertions.assertTrue(elapsed < 5_000L,
                "destroy() 应在 5s 内完成；got: " + elapsed + "ms");

        // destroy 第二次（idempotent）
        Assertions.assertDoesNotThrow(svc::destroy);

        // 关闭后仍能读 status
        var status = svc.getMonitorStatus();
        Assertions.assertNotNull(status);
        Assertions.assertEquals(false, status.get("monitoring"));

        // 给 watchFiles 线程一小段时间响应 ClosedWatchServiceException 退出
        Thread.sleep(500);

        // 不应有非 daemon 的 mtl-log-realtime 残留
        Set<Thread> threadSet = new HashSet<>(Thread.getAllStackTraces().keySet());
        for (Thread t : threadSet) {
            String name = t.getName();
            if (name == null) continue;
            if (name.contains("mtl-log-realtime") && !t.isDaemon()) {
                Assertions.fail("存在非 daemon 的 mtl-log-realtime 线程残留: " + name + " alive=" + t.isAlive());
            }
        }
    }
}