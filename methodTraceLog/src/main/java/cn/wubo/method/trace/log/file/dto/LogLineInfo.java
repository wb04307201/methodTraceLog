package cn.wubo.method.trace.log.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行信息类
 */
@Data
@AllArgsConstructor
public class LogLineInfo {

    private LocalDateTime timestamp;
    private String threadName;
    private String level;
    private String className;
    private String content;
    private String originalLine;

    /**
     * 判断当前日志行是否满足请求中的过滤条件。
     * <p>
     * 三道闸门按顺序生效：时间范围 → 级别 → 关键字（大小写不敏感）。
     * 任一不过都直接返回 false（短路求值）。
     *
     * @param request 查询条件（关键字 / 级别 / startTime / endTime）
     * @return true 表示该行应被保留
     */
    public boolean matchesFilter(LogQueryRequest request) {
        // 时间范围过滤
        if (!matchesTimeFilter(request)) {
            return false;
        }

        // 日志级别过滤
        if (!matchesLevelFilter(request)) {
            return false;
        }

        // 关键字过滤
        if (!matchesKeywordFilter(request)) {
            return false;
        }

        return true;
    }

    private boolean matchesTimeFilter(LogQueryRequest request) {
        if (timestamp == null) {
            return true; // 或者根据业务需求返回 false
        }

        if (request.getStartTime() != null && timestamp.isBefore(request.getStartTime())) {
            return false;
        }
        if (request.getEndTime() != null && timestamp.isAfter(request.getEndTime())) {
            return false;
        }
        return true;
    }

    private boolean matchesLevelFilter(LogQueryRequest request) {
        if (!StringUtils.hasText(request.getLevel())) {
            return true;
        }

        if (level == null) {
            return false;
        }

        return request.getLevel().equalsIgnoreCase(level);
    }

    private boolean matchesKeywordFilter(LogQueryRequest request) {
        if (!StringUtils.hasText(request.getKeyword())) {
            return true;
        }

        if (originalLine == null) {
            return false;
        }

        String keyword = request.getKeyword().toLowerCase();
        return originalLine.toLowerCase().contains(keyword);
    }

    /**
     * 用配置的 pattern 解析单行日志。
     * <p>
     * pattern 必须有 5 个捕获组：时间戳 / 线程名 / 级别 / logger / 消息。
     * 解析失败时返回只有 {@code originalLine} 填充的实例（其余字段 null），
     * 让上层过滤时仍能基于原始行做关键字匹配。
     *
     * @param line       原始日志行
     * @param logPattern 编译后的 pattern（来自 {@link cn.wubo.method.trace.log.MethodTraceLogProperties.FileProperties#logPattern}）
     * @return 解析结果，匹配失败时 {@code timestamp/threadName/level/className/content} 全为 null
     */
    public static LogLineInfo parse(String line, Pattern logPattern) {
        Matcher matcher = logPattern.matcher(line);
        if (matcher.find()) {
            String timestamp = matcher.group(1);    // 时间戳
            String threadName = matcher.group(2);      // 线程名
            String level = matcher.group(3);       // 日志级别
            String className = matcher.group(4);      // Logger 名称
            String content = matcher.group(5);     // 日志消息

            LocalDateTime dateTime = parseTimestamp(timestamp);
            return new LogLineInfo(dateTime, threadName, level, className, content, line);
        }

        // 如果不匹配标准格式，返回原始行
        return new LogLineInfo(null, null, null, null, line, line);
    }

    private static LocalDateTime parseTimestamp(String timestamp) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            return LocalDateTime.parse(timestamp, formatter);
        } catch (Exception e) {
            return null;
        }
    }
}
