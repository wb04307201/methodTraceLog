package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code /methodTraceLog/logFile/monitor/**} 状态机：
 * <ul>
 *   <li>{@code GET /logFile/monitor/start?fileName=...} — 开始 tail</li>
 *   <li>{@code GET /logFile/monitor/stop?fileName=...} — 停止 tail</li>
 *   <li>{@code GET /logFile/monitor/status} — 查询当前状态</li>
 * </ul>
 *
 * <p><b>关于 fileName（per Ruling 5 + 实际校验）：</b>
 * brief 原假设 {@code fileName="app-a.log"}，但 {@code MtlE2eHarness.primary} 设置的
 * {@code logging.file.name=logs/app-a.log} 会被 classpath 上的
 * {@code methodTraceLog-test/src/main/resources/logback.xml} 覆盖 —— 该文件硬编码
 * {@code <file>${LOG_DIR}/${APP_NAME}.log</file>} 且 {@code APP_NAME=myApp}。所以
 * 真正写入的日志文件是 {@code logs/myApp.log}。本测试用真实文件名。</p>
 *
 * <p><b>状态响应结构（per CLAUDE.md round 6 + LogFileRealTimeService.getMonitorStatus 实测）：</b>
 * 旧的 {@code currentFile} 字段已移除，response 现在是
 * {@code {type, monitoring:boolean, monitoredFiles:Set<String>, monitoredFilesCount:int}}。
 * 验证同时检查 {@code monitoring} 标志和 {@code monitoredFiles} 集合的成员关系 —— 这
 * 锁住了"开始监控后文件名真的进了集合"和"停止后集合真的被清空"两个不可降级的状态语义。</p>
 *
 * <p><b>全局状态清理（per Task 11 concerns）：</b>
 * 监控状态是 JVM 全局的（{@code LogFileRealTimeService.monitoredFiles} 进程内单例），
 * 所以即便测试本身最后调用了 stop，{@code @AfterAll} 里仍然再 stop 一次兜底，避
 * 免上一个失败 case 留下脏状态影响后续测试 / 后续 IT。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogFileMonitorIT {

    private static final String LOG_FILE_NAME = "myApp.log";
    private static final String STATUS_URL =
            "http://localhost:8085/methodTraceLog/logFile/monitor/status";
    private static final String START_URL =
            "http://localhost:8085/methodTraceLog/logFile/monitor/start?fileName=" + LOG_FILE_NAME;
    private static final String STOP_URL =
            "http://localhost:8085/methodTraceLog/logFile/monitor/stop?fileName=" + LOG_FILE_NAME;

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        // 兜底清理：如果测试本身因为断言失败没有走到 stop，全局 monitoredFiles 会留下脏状态。
        // 主动 stop 一次保证不影响后续 IT。
        try {
            if (host != null) {
                host.http().getForEntity(STOP_URL, String.class);
            }
        } catch (Exception ignored) {
            // 测试上下文可能已经关闭；忽略。
        }
        if (host != null) host.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void start_and_stop_monitor_changes_status() {
        // Step 1: start
        var startResp = host.http().getForEntity(START_URL, String.class);
        assertThat(startResp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/logFile/monitor/start should return 2xx; got %s body=%s",
                        startResp.getStatusCode(), startResp.getBody())
                .isTrue();

        // Step 2: status 应为 monitoring=true 且 monitoredFiles 包含目标文件名
        var statusResp = host.http().getForEntity(STATUS_URL, Map.class);
        assertThat(statusResp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/logFile/monitor/status should return 2xx; got %s",
                        statusResp.getStatusCode())
                .isTrue();

        Map<String, Object> body = (Map<String, Object>) statusResp.getBody();
        assertThat(body)
                .as("status response body should be a non-null map")
                .isNotNull();

        // 必须含 monitoring / monitoredFiles / monitoredFilesCount 三个键 —— 锁住 status 响应 schema
        assertThat(body).containsKeys("monitoring", "monitoredFiles", "monitoredFilesCount");

        // 强断言 1: monitoring=true
        assertThat(body.get("monitoring"))
                .as("after start, status.monitoring should be true; got body=%s", body)
                .isEqualTo(true);

        // 强断言 2: monitoredFiles 集合里真的有目标文件名（不是只看布尔位）
        Object filesRaw = body.get("monitoredFiles");
        assertThat(filesRaw)
                .as("status.monitoredFiles should be a non-null Collection; got: %s",
                        filesRaw == null ? "null" : filesRaw.getClass().getName())
                .isInstanceOf(Collection.class);
        Collection<Object> monitoredFiles = (Collection<Object>) filesRaw;
        assertThat(monitoredFiles)
                .as("after start, monitoredFiles should contain %s; got: %s",
                        LOG_FILE_NAME, monitoredFiles)
                .contains(LOG_FILE_NAME);

        // 强断言 3: monitoredFilesCount 等于集合大小
        assertThat(body.get("monitoredFilesCount"))
                .as("status.monitoredFilesCount should equal monitoredFiles.size()=%d; got body=%s",
                        monitoredFiles.size(), body)
                .isEqualTo(monitoredFiles.size());

        // Step 3: stop
        var stopResp = host.http().getForEntity(STOP_URL, String.class);
        assertThat(stopResp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/logFile/monitor/stop should return 2xx; got %s body=%s",
                        stopResp.getStatusCode(), stopResp.getBody())
                .isTrue();

        // Step 4: status 应回到 monitoring=false 且 monitoredFiles 为空
        var statusAfter = host.http().getForEntity(STATUS_URL, Map.class);
        assertThat(statusAfter.getStatusCode().is2xxSuccessful())
                .as("status after stop should return 2xx; got %s", statusAfter.getStatusCode())
                .isTrue();

        Map<String, Object> afterBody = (Map<String, Object>) statusAfter.getBody();
        assertThat(afterBody)
                .as("status response body after stop should be a non-null map")
                .isNotNull();

        assertThat(afterBody.get("monitoring"))
                .as("after stop, status.monitoring should be false; got body=%s", afterBody)
                .isEqualTo(false);

        Object afterFilesRaw = afterBody.get("monitoredFiles");
        assertThat(afterFilesRaw)
                .as("status.monitoredFiles after stop should be a non-null Collection; got: %s",
                        afterFilesRaw == null ? "null" : afterFilesRaw.getClass().getName())
                .isInstanceOf(Collection.class);
        Collection<Object> afterFiles = (Collection<Object>) afterFilesRaw;
        assertThat(afterFiles)
                .as("after stop, monitoredFiles should be empty; got: %s", afterFiles)
                .isEmpty();

        assertThat(afterBody.get("monitoredFilesCount"))
                .as("after stop, monitoredFilesCount should be 0; got body=%s", afterBody)
                .isEqualTo(0);
    }
}
