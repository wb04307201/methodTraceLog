package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code /methodTraceLog/logFile/**} 系列端点：
 * <ul>
 *   <li>{@code GET /logFile/files} — 列出日志目录下的可读文件</li>
 *   <li>{@code POST /logFile/query} — 按关键字过滤日志行</li>
 * </ul>
 *
 * <p><b>关于 fileName（per Ruling 5 + 实际校验）：</b>
 * brief 原假设 {@code fileName="app-a.log"}，但 {@code MtlE2eHarness.primary} 设置的
 * {@code logging.file.name=logs/app-a.log} 会被 classpath 上的
 * {@code methodTraceLog-test/src/main/resources/logback.xml} 覆盖 —— 该文件硬编码
 * {@code <file>${LOG_DIR}/${APP_NAME}.log</file>} 且 {@code APP_NAME=myApp}。所以
 * 真正写入的日志文件是 {@code logs/myApp.log}。本测试用真实文件名。</p>
 *
 * <p><b>关于 pageNum（per LogQueryRequest 实测）：</b>
 * brief 用 {@code pageNum} 字段，但 {@link cn.wubo.method.trace.log.file.dto.LogQueryRequest}
 * 的字段名是 {@code page}（{@code @Data} 生成 {@code setPage(int)}）。Jackson 按字段名
 * 序列化/反序列化，{@code pageNum} 不会落到 {@code page}。这里用 {@code page}。</p>
 *
 * <p><b>路径解析（per LogFileService.getFile）：</b>
 * {@code LogFileService.getFile(name)} 用 {@code new File(properties.getLogPath(), name)}，
 * 所以传 {@code "myApp.log"} 解析到 {@code ./logs/myApp.log}；{@code FileUtils.pathInspection}
 * 白名单 {@code [a-zA-Z0-9._-]+} 也允许该名。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogFileQueryIT {

    private static final String LOG_FILE_NAME = "myApp.log";

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void log_files_endpoint_returns_list() {
        var resp = host.http().getForEntity(
                "http://localhost:8085/methodTraceLog/logFile/files", List.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/logFile/files should return 2xx; got %s", resp.getStatusCode())
                .isTrue();

        List<Map<String, Object>> files = (List<Map<String, Object>>) resp.getBody();
        assertThat(files)
                .as("/logFile/files body should be a non-null, non-empty list")
                .isNotNull()
                .isNotEmpty();

        // 加强断言：列表里至少有一个 entry 的 name 以 ".log" 结尾（allowedExtensions 默认值之一）。
        boolean hasLogFile = files.stream().anyMatch(f -> {
            Object name = f.get("name");
            return name instanceof String s && s.toLowerCase().endsWith(".log");
        });
        assertThat(hasLogFile)
                .as("at least one file in /logFile/files should have a name ending in .log; got: %s", files)
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void log_query_returns_filtered_lines() {
        // 构造请求体 —— fileName 用真实日志文件名 "myApp.log"；page 用 LogQueryRequest 的实际字段名
        Map<String, Object> body = Map.of(
                "fileName", LOG_FILE_NAME,
                "keyword", "Started",
                "page", 1,
                "pageSize", 10
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        var resp = host.http().exchange(
                "http://localhost:8085/methodTraceLog/logFile/query",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST /methodTraceLog/logFile/query should return 2xx; got %s body=%s",
                        resp.getStatusCode(), resp.getBody())
                .isTrue();

        Map<String, Object> payload = resp.getBody();
        assertThat(payload)
                .as("response body should be a non-null LogQueryResponse map")
                .isNotNull();

        // LogQueryResponse.lines 是过滤后这一页的行列表
        Object linesRaw = payload.get("lines");
        assertThat(linesRaw)
                .as("response should have a non-null 'lines' field")
                .isNotNull();
        assertThat(linesRaw)
                .as("response 'lines' field should be a List")
                .isInstanceOf(List.class);

        List<Object> lines = (List<Object>) linesRaw;
        assertThat(lines)
                .as("keyword=Started should match Spring Boot startup banner (\"Started MethodTraceLogTestApplication in X seconds\"); got: %s", lines)
                .isNotEmpty();

        // 加强断言：返回的 lines 里至少有一行包含关键字 "Started"（大小写不敏感）
        boolean anyContainsStarted = lines.stream()
                .filter(o -> o instanceof String)
                .map(o -> (String) o)
                .anyMatch(s -> s.toLowerCase().contains("started"));
        assertThat(anyContainsStarted)
                .as("at least one returned line should contain keyword 'Started' (case-insensitive); got: %s", lines)
                .isTrue();

        // 顺带校验 totalLines/currentPage/totalPages 字段存在 —— 防止 LogQueryResponse 结构被破坏
        assertThat(payload).containsKey("totalLines");
        assertThat(payload).containsKey("currentPage");
        assertThat(payload).containsKey("totalPages");
    }
}