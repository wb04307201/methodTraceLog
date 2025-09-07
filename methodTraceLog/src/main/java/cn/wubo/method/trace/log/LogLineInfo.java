package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
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
