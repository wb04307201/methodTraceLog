package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

@Data
@ConfigurationProperties(prefix = "method-trace-log")
public class MethodTraceLogProperties {

    private LogProperties log = new LogProperties();

    private FileProperties file = new FileProperties();


    @Data
    public static class LogProperties {
        private Boolean enable = true;

        private List<ServiceCallProperties> serviceCalls = new ArrayList<>();


        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ServiceCallProperties {
            private String name;
            private Boolean enable = true;
        }
    }



    @Data
    public static class FileProperties {

        private Boolean enable = true;

        /**
         * 日志文件根目录
         */
        private String logPath = "./logs";

        /**
         * 允许访问的日志文件扩展名
         */
        private List<String> allowedExtensions = Arrays.asList(".log", ".txt", ".out");

        /**
         * 单次查询最大行数
         */
        private int maxLines = 1000;

        /**
         * 文件最大大小（MB）
         */
        private long maxFileSize = 100;

        /**
         * 日志文件匹配模式
         */
        private String logPattern = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s*-\\s*(.*)";
    }
}
