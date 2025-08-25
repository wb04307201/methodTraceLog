package cn.wubo.method.trace.log.utils;

import cn.wubo.method.trace.log.file.dto.LogQueryRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LogParserUtils {

    private LogParserUtils() {
    }

    private static final Pattern LOG_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+)\\s+(.*)");

    /**
     * 解析日志行，提取时间和级别
     */
    public static LogLineInfo parseLine(String line) {
        Matcher matcher = LOG_PATTERN.matcher(line);
        if (matcher.find()) {
            String timestamp = matcher.group(1);
            String level = matcher.group(2);
            String content = matcher.group(3);

            LocalDateTime dateTime = parseTimestamp(timestamp);
            return new LogLineInfo(dateTime, level, content, line);
        }

        // 如果不匹配标准格式，返回原始行
        return new LogLineInfo(null, null, line, line);
    }

    private static LocalDateTime parseTimestamp(String timestamp) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            return LocalDateTime.parse(timestamp, formatter);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 日志行信息类
     */
    @Data
    @AllArgsConstructor
    public static class LogLineInfo {
        private LocalDateTime timestamp;
        private String level;
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

    }
}