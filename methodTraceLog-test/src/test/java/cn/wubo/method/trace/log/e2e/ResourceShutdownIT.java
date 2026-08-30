package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 三个 known executor-service 在 Spring 上下文关闭后必须不留非 daemon 线程。
 * <p>
 * 风险 R-14 / R-19 / R-20：AlertingService / MtlSessionService / SimpleMonitorServiceImpl
 * 各自启动 daemon 线程池。daemon 不阻止 JVM 退出，但如果它们没正确响应线程中断，
 * 测试 harness（JVM 不退出）会出现"看着像僵尸"的残留线程。
 * <p>
 * 验证：
 *  <ul>
 *      <li>关闭 harness 后，线程快照里没有非 daemon 线程名包含
 *          "mtl-alerting-webhook" / "mtl-session-cleanup" / "mtl-monitor-cleanup"</li>
 *      <li>关闭用时在合理范围（< 30s）</li>
 *  </ul>
 */
class ResourceShutdownIT {

    @Test
    void harness_closesClean_noLingerinNonDaemonExecutorThreads() {
        long start = System.currentTimeMillis();
        try (MtlE2eHarness host = MtlE2eHarness.primary(8101, java.util.Map.of())) {
            // 不主动调用任何 endpoint；只构造 + 关闭
            Assertions.assertNotNull(host);
        }
        long elapsed = System.currentTimeMillis() - start;
        Assertions.assertTrue(elapsed < 30_000L,
                "harness 启动+关闭应在 30s 内完成；got: " + elapsed + "ms");

        // 给 ScheduledExecutorService / Tomcat 一点点时间响应中断
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Set<String> lingeringNonDaemon = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name == null || t.isDaemon()) continue;
            if (name.startsWith("mtl-alerting-webhook")
                    || name.startsWith("mtl-session-cleanup")
                    || name.startsWith("mtl-monitor-cleanup")
                    || name.contains("mtl-log-realtime")) {
                lingeringNonDaemon.add(name);
            }
        }
        Assertions.assertTrue(lingeringNonDaemon.isEmpty(),
                "harness 关闭后存在非 daemon 线程残留: " + lingeringNonDaemon);
    }
}