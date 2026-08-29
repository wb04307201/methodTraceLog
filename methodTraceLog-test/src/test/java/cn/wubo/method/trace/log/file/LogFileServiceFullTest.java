package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.file.dto.LogQueryResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * LogFileService 单元测试：覆盖 queryLogs 与 downloadLog 的完整路径。
 * <p>
 * 已有的 LogFileServiceSizeFormatTest 只覆盖 formatSize 这一个静态工具方法。
 * 本测试补全 service 实例方法的全路径：分页 / 关键字过滤 / 时间过滤 / level 过滤 /
 * reverse 排序 / downloadLog 的过滤 + reverse 行为 / getLogFiles 列目录 / 非法输入 4xx。
 */
class LogFileServiceFullTest {

    private MethodTraceLogProperties.FileProperties props;
    private LogFileService service;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        props = new MethodTraceLogProperties.FileProperties();
        props.setLogPath(dir.toString());
        props.setAllowedExtensions(Arrays.asList(".log"));
        // 实际生产默认值 10000 行；测试时设小一些跑得快
        props.setScanLines(1000);
        service = new LogFileService(props);

        // 写一个符合默认 pattern 的日志文件
        Path file = dir.resolve("app.log");
        Files.writeString(file, """
                2024-01-01 10:00:00.000 [main] INFO  com.example.App - Application started
                2024-01-01 10:00:01.000 [main] DEBUG com.example.App - Debug message here
                2024-01-01 10:00:02.000 [main] INFO  com.example.App - Processing request
                2024-01-01 10:00:03.000 [worker-1] WARN  com.example.App - Slow query
                2024-01-01 10:00:04.000 [worker-1] ERROR com.example.App - NullPointerException
                2024-01-01 10:00:05.000 [worker-1] INFO  com.example.App - Recovered
                2024-01-01 10:00:06.000 [main] INFO  com.example.App - Application ready
                """);
    }

    @Test
    void queryLogs_returnsAllLines_whenNoFilter() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(1);
        req.setPageSize(100);
        // reverse 默认 true → 最新行在前
        LogQueryResponse resp = service.queryLogs(req);

        Assertions.assertEquals(7, resp.getTotalLines());
        Assertions.assertEquals(7, resp.getLines().size());
        // 最新行（"Application ready"）应在第一行（reverse=true 时）
        Assertions.assertTrue(resp.getLines().get(0).contains("Application ready"),
                "reverse=true → 最新行应在前面；got: " + resp.getLines().get(0));
    }

    @Test
    void queryLogs_filtersByKeyword() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setKeyword("Slow");
        req.setPage(1);
        req.setPageSize(100);

        LogQueryResponse resp = service.queryLogs(req);
        // 应只剩 1 行 "Slow query"
        Assertions.assertEquals(1, resp.getTotalLines());
        Assertions.assertTrue(resp.getLines().get(0).contains("Slow query"));
    }

    @Test
    void queryLogs_filtersByLevel() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setLevel("ERROR");
        req.setPage(1);
        req.setPageSize(100);

        LogQueryResponse resp = service.queryLogs(req);
        Assertions.assertTrue(resp.getTotalLines() >= 1);
        for (String line : resp.getLines()) {
            Assertions.assertTrue(line.contains("ERROR"),
                    "filter level=ERROR 后所有行必须含 ERROR；got: " + line);
        }
    }

    @Test
    void queryLogs_pagination_clampsTotalPages() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(1);
        req.setPageSize(3);

        LogQueryResponse resp = service.queryLogs(req);
        Assertions.assertEquals(7, resp.getTotalLines());
        Assertions.assertEquals(3, resp.getLines().size());
        Assertions.assertEquals(1, resp.getCurrentPage());
        Assertions.assertTrue(resp.getTotalPages() >= 3, "totalPages 应向上取整到至少 3；got: " + resp.getTotalPages());
    }

    @Test
    void queryLogs_reverseFalse_oldestFirst() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setReverse(false);
        req.setPage(1);
        req.setPageSize(100);

        LogQueryResponse resp = service.queryLogs(req);
        Assertions.assertEquals(7, resp.getLines().size());
        // reverse=false → 最旧行 "Application started" 在前
        Assertions.assertTrue(resp.getLines().get(0).contains("Application started"),
                "reverse=false → 最旧行应在前面；got: " + resp.getLines().get(0));
    }

    @Test
    void queryLogs_timeRangeFilter() throws Exception {
        // 10:00:02 ~ 10:00:04 范围内
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setStartTime(LocalDateTime.parse("2024-01-01T10:00:02"));
        req.setEndTime(LocalDateTime.parse("2024-01-01T10:00:04"));
        req.setPage(1);
        req.setPageSize(100);

        LogQueryResponse resp = service.queryLogs(req);
        Assertions.assertTrue(resp.getTotalLines() >= 3,
                "时间窗 [10:00:02, 10:00:04] 应至少 3 行（10:00:02 / 03 / 04）；got: " + resp.getTotalLines());
    }

    @Test
    void queryLogs_nonexistentFile_throws() {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("does-not-exist.log");

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.queryLogs(req));
    }

    @Test
    void queryLogs_disallowedExtension_throws() throws Exception {
        // 写一个 .exe 文件
        Path bad = props.getLogPath() != null
                ? Path.of(props.getLogPath(), "evil.exe")
                : Path.of("evil.exe");
        // 用 props 的实际路径解析
        Path root = Path.of(props.getLogPath());
        Path badReal = root.resolve("evil.exe");
        Files.writeString(badReal, "nope");

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("evil.exe");
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.queryLogs(req));
    }

    @Test
    void downloadLog_filtersAndReverse() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setKeyword("INFO");
        req.setReverse(false);

        List<String> lines = service.downloadLog(req);
        Assertions.assertFalse(lines.isEmpty());
        // 所有行必须含 INFO；reverse=false → 最旧 INFO 行在前
        for (String line : lines) {
            Assertions.assertTrue(line.contains("INFO"),
                    "downloadLog + keyword=INFO 后每行必须含 INFO；got: " + line);
        }
        Assertions.assertTrue(lines.get(0).contains("Application started"),
                "reverse=false → 第一行是最旧 INFO；got: " + lines.get(0));
    }

    @Test
    void downloadLog_empty_whenNoMatch() throws Exception {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setKeyword("__no_such_token__");

        List<String> lines = service.downloadLog(req);
        Assertions.assertTrue(lines.isEmpty());
    }

    @Test
    void getLogFiles_listsValidExtensionsOnly(@TempDir Path tmpDir) throws IOException {
        // 替换 props 到一个全新目录
        MethodTraceLogProperties.FileProperties p2 = new MethodTraceLogProperties.FileProperties();
        p2.setLogPath(tmpDir.toString());
        p2.setAllowedExtensions(Arrays.asList(".log"));
        Files.writeString(tmpDir.resolve("a.log"), "x");
        Files.writeString(tmpDir.resolve("b.txt"), "x"); // 不在 allowed

        LogFileService svc = new LogFileService(p2);
        var files = svc.getLogFiles();
        Assertions.assertEquals(1, files.size(),
                "应只列出 .log 文件；got: " + files);
        Assertions.assertEquals("a.log", files.get(0).get("name"));
    }
}