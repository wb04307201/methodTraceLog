package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.dto.LogLineInfo;
import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import cn.wubo.method.trace.log.file.dto.LogQueryResponse;
import cn.wubo.method.trace.log.utils.FileUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
                // 过滤出有效的日志文件
                .filter(this::isValidFileExtensions)
                // 将文件信息转换为Map对象
                .map(file -> Map.<String, Object>of("name", file.getName(), "size", file.length(), "lastModified", file.lastModified(), "readable", file.canRead())).toList();
    }


    /**
     * 验证文件扩展名是否有效
     *
     * @param file 待验证的文件对象
     * @return 如果文件扩展名在允许的扩展名列表中则返回true，否则返回false
     */
    private Boolean isValidFileExtensions(File file) {
        // 获取文件名并转换为小写，用于后续扩展名匹配
        String fileName = file.getName().toLowerCase();
        // 检查文件名是否以允许的扩展名结尾
        return properties.getAllowedExtensions().stream().anyMatch(fileName::endsWith);
    }


    public LogQueryResponse queryLogs(LogQueryRequest request) throws IOException {
        String fileName = request.getFileName();

        File logFile = getFile(fileName);

        List<String> allLines = Files.readAllLines(logFile.toPath());

        // 过滤日志行
        List<String> filteredLines = filterLines(allLines, request);

        // 倒序处理
        if (request.isReverse()) {
            Collections.reverse(filteredLines);
        }

        // 分页处理
        int totalLines = filteredLines.size();
        int totalPages = (int) Math.ceil((double) totalLines / request.getPageSize());
        int startIndex = (request.getPage() - 1) * request.getPageSize();
        int endIndex = Math.min(startIndex + request.getPageSize(), totalLines);

        List<String> pageLines = filteredLines.subList(startIndex, endIndex);

        LogQueryResponse response = new LogQueryResponse();
        response.setLines(pageLines);
        response.setTotalLines(totalLines);
        response.setCurrentPage(request.getPage());
        response.setTotalPages(totalPages);
        response.setFileSize(logFile.length());
        response.setLastModified(LocalDateTime.ofInstant(Instant.ofEpochMilli(logFile.lastModified()), ZoneId.systemDefault()));

        return response;

    }

    private List<String> filterLines(List<String> lines, LogQueryRequest request) {
        if (!hasFilter(request)) {
            return lines;
        }

        return lines.stream()
                .map(line -> LogLineInfo.parse(line, logPattern))
                .filter(lineInfo -> lineInfo.matchesFilter(request))
                .map(LogLineInfo::getOriginalLine)
                .collect(Collectors.toList());
    }

    /**
     * 检查日志查询请求是否包含过滤条件
     *
     * @param request 日志查询请求对象，可能为null
     * @return 如果请求包含任意过滤条件（关键词、级别、开始时间、结束时间）则返回true，否则返回false
     */
    private boolean hasFilter(LogQueryRequest request) {
        if (request == null) {
            return false;
        }
        // 检查是否设置了任意过滤条件：关键词、级别、时间范围
        return StringUtils.hasText(request.getKeyword()) || StringUtils.hasText(request.getLevel()) || request.getStartTime() != null || request.getEndTime() != null;
    }

    private File getFile(String fileName) {
        // 安全检查：防止路径遍历攻击和非法文件名
        FileUtils.pathInspection(fileName);

        File logFile = new File(properties.getLogPath(), fileName);

        if (!logFile.exists()) {
            throw new IllegalArgumentException("File does not exist");
        }
        if (!logFile.isFile()) {
            throw new IllegalArgumentException("Not a valid file");
        }
        boolean isValidFileExtensions = isValidFileExtensions(logFile);
        if (!isValidFileExtensions) {
            throw new IllegalArgumentException("Unsupported file type");
        }

        long fileSizeMB = logFile.length() / (1024 * 1024);
        if (fileSizeMB > properties.getMaxFileSize()) {
            throw new IllegalArgumentException(String.format("File too large, exceeds limit of %dMB", properties.getMaxFileSize()));
        }

        return logFile;
    }

    public List<String> downloadLog(LogQueryRequest request) throws IOException {
        File logFile = getFile(request.getFileName());
        List<String> allLines = Files.readAllLines(logFile.toPath());
        return filterLines(allLines, request);
    }

}
