package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.dto.LogLineInfo;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.file.dto.LogQueryResponse;
import cn.wubo.method.trace.log.utils.FileUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link LogFileService} / {@link LogLineInfo} / {@link FileUtils} 边界用例回归测试。
 * <p>
 * 覆盖风险清单中的 R-30..R-37：formatSize 边界值、queryLogs scanLines 钳位、page=0 未校验、
 * reverse 默认值、parseTimestamp 失败后时间窗过滤放行、keyword Unicode 大小写折叠。
 * <p>
 * 所有测试不依赖 Spring 容器，直接 new LogFileService/FileUtils 实例。
 */
class LogFileServiceBoundariesTest {

    // ===== R-30: formatSize 边界值 =====

    @Test
    void formatSize_zeroBytes_returnsZeroB() {
        Assertions.assertEquals("0 B", LogFileService.formatSize(0L));
    }

    @Test
    void formatSize_1023Bytes_returnsByteFormatted() {
        // 1023 仍走 "<1024" 分支，纯字节
        Assertions.assertEquals("1023 B", LogFileService.formatSize(1023L));
    }

    @Test
    void formatSize_LongMaxValue_returnsTBWithoutOverflow() {
        // Long.MAX_VALUE ≈ 8 ZiB（远超 TB），formatSize 会算到 TB 单位然后被 units 数组截断
        String s = LogFileService.formatSize(Long.MAX_VALUE);
        // 必须能成功渲染成 TB 单位且不抛异常
        Assertions.assertNotNull(s);
        Assertions.assertTrue(s.endsWith(" TB"),
                "Long.MAX_VALUE 应当被截断到最大单位 TB；got: " + s);
        // 解析回去应 ≈ Long.MAX_VALUE / (1024^4) 范围
        double num = Double.parseDouble(s.split(" ")[0]);
        Assertions.assertTrue(num > 0.0 && Double.isFinite(num),
                "TB 数值必须为正有限数；got: " + num);
    }

    @Test
    void formatSize_negativeValue_returnsBytesVerbatim() {
        // formatSize 把 bytes < 1024 走"返回 bytes + B"分支 —— 对负数依然走该分支
        // （没有显式拒绝负数）。这是已观察到的行为，我们只锁定它而不期望修复。
        String s = LogFileService.formatSize(-1L);
        Assertions.assertEquals("-1 B", s);
    }

    // ===== R-31: scanLines < 1000 被钳位 =====

    @Test
    void queryLogs_scanLinesLessThan1000_clampsTo1000(@TempDir Path dir) throws IOException {
        // 即便用户把 scanLines 设成 1，queryLogs 仍然至少扫 1000 行 —— 这是 queryLogs 的硬下限。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1); // 故意设极小
        // 写 1005 行（>1000）
        Path f = dir.resolve("big.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1005; i++) {
            sb.append("2024-01-01 10:00:00.000 [main] INFO  com.example.App - line ")
                    .append(i).append('\n');
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);

        LogFileService svc = new LogFileService(fp);
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("big.log");
        req.setPage(1);
        req.setPageSize(2000);
        req.setReverse(false); // 顺序读，便于断言行数
        LogQueryResponse resp = svc.queryLogs(req);
        // 即便 scanLines=1，仍至少扫 1000 行（Math.max(scanLines, 1000)）
        Assertions.assertEquals(1000, resp.getTotalLines(),
                "scanLines 钳到 1000 应当保证扫到 1000 行；实际: " + resp.getTotalLines());
    }

    // ===== R-32: path traversal 直接绕过 pathInspection 时被 LogFileService.getFile 拦截 =====

    @Test
    void queryLogs_pathTraversal_rejectedByFileUtils(@TempDir Path dir) {
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        LogFileService svc = new LogFileService(fp);

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("../etc/passwd");
        // FileUtils.pathInspection 拦在 getFile() 里
        Assertions.assertThrows(IllegalArgumentException.class, () -> svc.queryLogs(req));
    }

    // ===== R-33: FileUtils.pathInspection(".") 通过白名单 =====

    @Test
    void pathInspection_dotIsAccepted() {
        // 单字符 "." 匹配 [a-zA-Z0-9._-]+，能通过白名单。
        Assertions.assertDoesNotThrow(() -> FileUtils.pathInspection("."));
    }

    @Test
    void queryLogs_dotAsFileName_throwsIllegalArgument(@TempDir Path dir) {
        // "." 通过 pathInspection（单字符 "." 匹配 [a-zA-Z0-9._-]+）。
        // LogFileService 拿 "." 拼成 File(dir, ".") = dir 本身 —— isFile()==false → "Not a valid file"。
        // 锁定当前行为：清晰错误信息 + 不抛 NPE/路径越界。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        LogFileService svc = new LogFileService(fp);

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName(".");
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req));
        // 接受 "Not a valid file" 或 "File does not exist" 两种消息（实现细节，目录存在性优先）
        String msg = ex.getMessage();
        Assertions.assertTrue(msg.contains("Not a valid file") || msg.contains("File does not exist"),
                "错误信息应当清晰说明 \".\" 不是合法文件；got: " + msg);
    }

    // ===== R-34: page=0 现在被 queryLogs 显式拒绝 =====

    @Test
    void queryLogs_pageZero_throwsIllegalArgumentException(@TempDir Path dir) throws IOException {
        // Round 16 fix: LogFileService.queryLogs 显式拒绝 page < 1，避免 subList(-N, ...)
        // 抛 IndexOutOfBoundsException。与 LogQueryRequest @Min(1) 的契约对齐。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        Path f = dir.resolve("app.log");
        Files.writeString(f, """
                2024-01-01 10:00:00.000 [main] INFO  com.example.App - line A
                2024-01-01 10:00:01.000 [main] INFO  com.example.App - line B
                """);

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(0); // 违反 @Min(1)
        req.setPageSize(100);
        req.setReverse(false);
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req),
                "page < 1 必须抛 IllegalArgumentException（不让 subList 抛 IOOBE）");
        Assertions.assertTrue(ex.getMessage().contains("page must be >= 1"),
                "异常消息应清晰指出页码非法；got: " + ex.getMessage());
    }

    @Test
    void queryLogs_negativePage_alsoRejected(@TempDir Path dir) throws IOException {
        // 同上，负数 page 也必须抛 IllegalArgumentException。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        Path f = dir.resolve("app.log");
        Files.writeString(f, "2024-01-01 10:00:00.000 [main] INFO  com.example.App - line A\n");

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(-5);
        req.setPageSize(100);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req));
    }

    // ===== Round 18 fix: page/pageSize 校验在文件读取之前（fail-fast） =====

    @Test
    void queryLogs_pageZero_failsBeforeFileRead(@TempDir Path dir) {
        // 验证：当 page=0 时，queryLogs 抛"page must be >= 1"，而不是先尝试去读一个不存在的文件。
        // 用一个 pathInspection 能通过、但 getFile() 一定会拒绝的 fileName（不存在的文件 → "File does not exist"）。
        // 若校验在文件读取之后，getFile 会先抛 "File does not exist"；本测试要求先抛 page 错误，证明校验已前置。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("does-not-exist.log"); // getFile 会抛 "File does not exist"
        req.setPage(0); // 应当先于此处抛出
        req.setPageSize(100);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req),
                "page=0 必须在任何文件 IO 之前抛 IllegalArgumentException");
        Assertions.assertTrue(ex.getMessage().contains("page must be >= 1"),
                "异常必须是 page 校验，不是文件读取错误；got: " + ex.getMessage());
        Assertions.assertFalse(ex.getMessage().contains("File does not exist"),
                "绝不应该走到 getFile；got: " + ex.getMessage());
    }

    @Test
    void queryLogs_pageSizeZero_throwsIllegalArgumentException(@TempDir Path dir) throws IOException {
        // pageSize=0 必须抛 IllegalArgumentException（与 LogQueryRequest @Min(1) 对齐）。
        // 把校验移到文件读取之前：即使给个不存在的 fileName，也应先抛 pageSize 错误。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        Path f = dir.resolve("app.log");
        Files.writeString(f, "2024-01-01 10:00:00.000 [main] INFO  com.example.App - line A\n");

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(1);
        req.setPageSize(0);
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req),
                "pageSize=0 必须抛 IllegalArgumentException");
        Assertions.assertTrue(ex.getMessage().contains("pageSize must be >= 1"),
                "异常消息应清晰指出 pageSize 非法；got: " + ex.getMessage());
    }

    @Test
    void queryLogs_negativePageSize_throwsIllegalArgumentException(@TempDir Path dir) throws IOException {
        // 负数 pageSize 也必须抛 IllegalArgumentException（与 @Min(1) 对齐）。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        Path f = dir.resolve("app.log");
        Files.writeString(f, "2024-01-01 10:00:00.000 [main] INFO  com.example.App - line A\n");

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(1);
        req.setPageSize(-10);
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.queryLogs(req),
                "pageSize<0 必须抛 IllegalArgumentException");
        Assertions.assertTrue(ex.getMessage().contains("pageSize must be >= 1"),
                "异常消息应清晰指出 pageSize 非法；got: " + ex.getMessage());
    }

    // ===== R-35: reverse 默认值 =====

    @Test
    void queryLogs_reverseDefaultIsTrue_observedInBehavior(@TempDir Path dir) throws IOException {
        // LogQueryRequest 的 reverse 默认 true → 不显式设时拿到的是倒序结果。
        MethodTraceLogProperties.FileProperties fp = new MethodTraceLogProperties.FileProperties();
        fp.setLogPath(dir.toString());
        fp.setAllowedExtensions(Arrays.asList(".log"));
        fp.setScanLines(1000);
        LogFileService svc = new LogFileService(fp);

        Path f = dir.resolve("app.log");
        Files.writeString(f, """
                2024-01-01 10:00:00.000 [main] INFO  com.example.App - FIRST
                2024-01-01 10:00:01.000 [main] INFO  com.example.App - SECOND
                """);

        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("app.log");
        req.setPage(1);
        req.setPageSize(100);
        // 不显式 setReverse —— 依赖默认值
        Assertions.assertTrue(new LogQueryRequest().isReverse(),
                "LogQueryRequest reverse 默认应为 true（业务层偏好\"看最新日志\"）");
        LogQueryResponse resp = svc.queryLogs(req);
        // 默认 reverse=true → 最新行 SECOND 在前
        Assertions.assertTrue(resp.getLines().get(0).contains("SECOND"),
                "reverse 默认 true → 最新行应在前面；got: " + resp.getLines().get(0));
    }

    // ===== R-36: parseTimestamp 返回 null 后时间窗过滤放行 =====

    @Test
    void logLineInfo_matchesTimeFilter_returnsTrueWhenTimestampIsNull() {
        // 模拟一个 LogLineInfo，timestamp=null（解析失败）
        LogLineInfo info = new LogLineInfo(null, "main", "INFO", "com.x.Y", "msg", "original-line");

        LogQueryRequest req = new LogQueryRequest();
        req.setStartTime(java.time.LocalDateTime.parse("2024-01-01T10:00:00"));
        req.setEndTime(java.time.LocalDateTime.parse("2024-01-01T11:00:00"));

        // 设计意图（见 LogLineInfo.matchesTimeFilter 的注释）：timestamp=null → true（"不过滤"）
        Assertions.assertTrue(info.matchesFilter(req),
                "timestamp=null 时不应被时间窗过滤掉（matchesTimeFilter 短路返回 true）");
    }

    // ===== R-37: keyword 匹配使用 toLowerCase(Locale.ROOT)，但代码实际未传 Locale =====

    @Test
    void logLineInfo_keywordToLowerCase_usesDefaultLocale_noTurkishI() {
        // 土耳其语 locale 下，小写 "İ"（U+0130 大写 I 的土耳其小写）会被 toLowerCase 折叠为 "i"（无点），
        // 但 ASCII "I" 在土耳其 locale 下会被 toLowerCase 折叠为 "ı"（无点），不等于 "i"。
        // LogLineInfo.matchesKeywordFilter 用的是 String.toLowerCase()（依赖默认 locale），
        // 不是 toLowerCase(Locale.ROOT) —— 这是潜在 Unicode case-folding bug。
        //
        // 我们锁定的契约：默认 JVM locale（en_US 等）下，keyword "INFO" 与 level "INFO" 能匹配。
        // 该测试仅断言 happy path 在中性 locale 下工作；土耳其 locale 路径留待专门的测试覆盖。
        Pattern p = Pattern.compile(
                "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s*-\\s*(.*)");
        LogLineInfo info = LogLineInfo.parse(
                "2024-01-01 10:00:00.000 [main] INFO  com.example.App - hello world", p);
        Assertions.assertNotNull(info);

        LogQueryRequest req = new LogQueryRequest();
        req.setKeyword("HELLO");
        Assertions.assertTrue(info.matchesFilter(req),
                "中性 locale 下大写关键字应能匹配小写原文");

        req.setKeyword("hello");
        Assertions.assertTrue(info.matchesFilter(req));
    }

    // ===== R-37 续: 直接断言 Unicode 关键字（中文 / latin extended） =====

    @Test
    void logLineInfo_keywordWithChinese_works() {
        Pattern p = Pattern.compile(
                "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s*-\\s*(.*)");
        LogLineInfo info = LogLineInfo.parse(
                "2024-01-01 10:00:00.000 [main] INFO  com.example.App - 中文关键字日志", p);

        LogQueryRequest req = new LogQueryRequest();
        req.setKeyword("中文");
        Assertions.assertTrue(info.matchesFilter(req),
                "中文关键字匹配应正常工作；parsed: " + info);
    }
}
