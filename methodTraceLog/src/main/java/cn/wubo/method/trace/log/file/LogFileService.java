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

public class LogFileService {

    private final MethodTraceLogProperties.FileProperties properties;
    private final Pattern logPattern;

    public LogFileService(MethodTraceLogProperties.FileProperties properties) {
        this.properties = properties;
        this.logPattern = Pattern.compile(properties.getLogPattern());
    }

    /**
     * 获取日志文件列表
     *
     * @return 日志文件信息列表，每个元素包含文件名、大小、最后修改时间和可读性信息
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
                .map(file -> Map.<String, Object>of(
                        "name", file.getName(),
                        "size", file.length(),
                        "lastModified", file.lastModified(),
                        "readable", file.canRead()))
                .toList();
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
     */
    public LogQueryResponse queryLogs(LogQueryRequest request) throws IOException {
        String fileName = request.getFileName();

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
