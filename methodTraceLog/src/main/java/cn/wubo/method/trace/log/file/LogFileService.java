package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.dto.LogLineInfo;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.file.dto.LogQueryResponse;
import cn.wubo.method.trace.log.utils.FileUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 日志文件服务：列表 / 分页查询 / 流式下载。
 * <p>
 * 设计要点：
 *  1. 用 {@link Files#lines} 流式读，避免 {@code readAllLines} 一次吃完整文件。
 *  2. 通过 {@link MethodTraceLogProperties.FileProperties#scanLines} 限制单次扫描行数。
 *  3. 过滤依赖 {@link LogLineInfo} 解析，pattern 在构造时编译。
 *  4. 不做 size 预检 —— {@code Files.lines()+limit} 是懒加载，文件本身多大都支持。
 */
public class LogFileService {

    private final MethodTraceLogProperties.FileProperties properties;
    private final Pattern logPattern;

    /**
     * 构造方法。提前编译 pattern，避免每次查询都重新编译。
     *
     * @param properties 文件相关配置（路径、扩展名、scanLines、logPattern）
     */
    public LogFileService(MethodTraceLogProperties.FileProperties properties) {
        this.properties = properties;
        this.logPattern = Pattern.compile(properties.getLogPattern());
    }

    /**
     * 获取日志文件列表
     *
     * @return 日志文件信息列表，每个元素包含文件名、大小、人类可读大小、最后修改时间和可读性信息
     */
    public List<Map<String, Object>> getLogFiles() {
        File logDir = new File(properties.getLogPath());
        if (!logDir.exists() || !logDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = logDir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        return Arrays.stream(files)
                .filter(this::isValidFileExtensions)
                .map(file -> {
                    long bytes = file.length();
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", file.getName());
                    m.put("size", bytes);
                    m.put("humanReadableSize", formatSize(bytes));
                    m.put("lastModified", file.lastModified());
                    m.put("readable", file.canRead());
                    return m;
                })
                .toList();
    }

    /**
     * 把字节数渲染为人类可读字符串。
     * <p>
     * 单位从 B → KB → MB → GB → TB 自动升级，保留 1 位小数；不足 1KB 的按整数字节输出。
     *
     * @param bytes 文件字节数（≥0）
     * @return 例如 "1 B" / "1.0 KB" / "1.5 MB" / "150.5 GB"
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int u = 0;
        while (v >= 1024.0 && u < units.length - 1) {
            v /= 1024.0;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }


    /**
     * 验证文件扩展名是否有效
     */
    private Boolean isValidFileExtensions(File file) {
        String fileName = file.getName().toLowerCase();
        return properties.getAllowedExtensions().stream().anyMatch(fileName::endsWith);
    }


    /**
     * 分页查询日志。
     * <p>
     * 实现要点：
     *  1. 用 Files.lines() 流式读取，避免 readAllLines 一次载入整个文件
     *  2. 限制最大扫描行数（防止 N GB 文件扫太久，也是内存的实际安全网）
     *  3. 反向：直接在流中反向收集（reverse=true 业务场景是"看最新日志"）
     *
     * @param request 查询条件（文件名、关键字、级别、时间范围、分页）
     * @return 分页结果（行列表 + 总行数 + 当前页 + 总页数 + 文件元信息）
     * @throws IOException 读取文件失败
     */
    public LogQueryResponse queryLogs(LogQueryRequest request) throws IOException {
        String fileName = request.getFileName();

        // 防御：page / pageSize 非法 → IllegalArgumentException（与 LogQueryRequest @Min(1) 的契约对齐）。
        // 否则 startIndex = (page - 1) * pageSize 会变成负数，subList(-N, ...) 直接抛
        // IndexOutOfBoundsException —— 用户看到的是底层 NPE / IOOBE，不是"分页参数非法"的清晰错误。
        // 这一段必须在文件读取之前：fail-fast，避免无效请求付出 IO + 解析 + 过滤的全量代价。
        if (request.getPage() < 1) {
            throw new IllegalArgumentException("page must be >= 1, got: " + request.getPage());
        }
        if (request.getPageSize() < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1, got: " + request.getPageSize());
        }

        File logFile = getFile(fileName);

        int maxScanLines = Math.max(properties.getScanLines(), 1000);
        List<String> filteredLines;
        try (Stream<String> stream = Files.lines(logFile.toPath(), StandardCharsets.UTF_8)) {
            Stream<String> limited = stream.limit(maxScanLines);
            filteredLines = filterLines(limited, request);
        }

        if (request.isReverse()) {
            Collections.reverse(filteredLines);
        }

        int totalLines = filteredLines.size();
        // belt-and-braces：上面的早返回已经做了 page<1/pageSize<1 校验；这里保留同样校验
        // 作为防御性深度防御 —— 即便校验被未来的重构挪走，下面算 startIndex 的逻辑仍然安全。
        if (request.getPage() < 1) {
            throw new IllegalArgumentException("page must be >= 1, got: " + request.getPage());
        }
        int totalPages = (int) Math.ceil((double) totalLines / request.getPageSize());
        int startIndex = (request.getPage() - 1) * request.getPageSize();
        int endIndex = Math.min(startIndex + request.getPageSize(), totalLines);

        List<String> pageLines = startIndex < totalLines
                ? new ArrayList<>(filteredLines.subList(startIndex, endIndex))
                : Collections.emptyList();

        LogQueryResponse response = new LogQueryResponse();
        response.setLines(pageLines);
        response.setTotalLines(totalLines);
        response.setCurrentPage(request.getPage());
        response.setTotalPages(totalPages);
        response.setFileSize(logFile.length());
        response.setLastModified(LocalDateTime.ofInstant(Instant.ofEpochMilli(logFile.lastModified()), ZoneId.systemDefault()));

        return response;
    }

    private List<String> filterLines(Stream<String> stream, LogQueryRequest request) {
        boolean needFilter = hasFilter(request);
        Stream<String> filtered = needFilter
                ? stream
                    .map(line -> LogLineInfo.parse(line, logPattern))
                    .filter(lineInfo -> lineInfo.matchesFilter(request))
                    .map(LogLineInfo::getOriginalLine)
                : stream;
        return filtered.collect(Collectors.toList());
    }

    private boolean hasFilter(LogQueryRequest request) {
        if (request == null) {
            return false;
        }
        return StringUtils.hasText(request.getKeyword())
                || StringUtils.hasText(request.getLevel())
                || request.getStartTime() != null
                || request.getEndTime() != null;
    }

    private File getFile(String fileName) {
        FileUtils.pathInspection(fileName);

        File logFile = new File(properties.getLogPath(), fileName);

        if (!logFile.exists()) {
            throw new IllegalArgumentException("File does not exist: " + fileName);
        }
        if (!logFile.isFile()) {
            throw new IllegalArgumentException("Not a valid file: " + fileName);
        }
        if (!isValidFileExtensions(logFile)) {
            throw new IllegalArgumentException("Unsupported file type: " + fileName);
        }

        // 注意:这里不做 size 校验。Files.lines() + limit(scanLines) 是流式懒加载,
        // 真实内存占用只跟 scanLines 有关、跟文件大小无关,大日志文件能正常查询。

        return logFile;
    }

    /**
     * 下载日志（流式过滤，避免一次性加载）。
     *
     * @param request 查询条件（文件名 + 可选过滤 + reverse），分页参数被忽略
     * @return 过滤后的日志行列表；可被直接写入 {@code text/plain} 响应
     * @throws IOException 读取文件失败
     */
    public List<String> downloadLog(LogQueryRequest request) throws IOException {
        File logFile = getFile(request.getFileName());
        int maxScanLines = Math.max(properties.getScanLines(), 1000);
        try (Stream<String> stream = Files.lines(logFile.toPath(), StandardCharsets.UTF_8)) {
            Stream<String> limited = stream.limit(maxScanLines);
            List<String> result = filterLines(limited, request);
            if (request.isReverse()) {
                Collections.reverse(result);
            }
            return result;
        }
    }
}
