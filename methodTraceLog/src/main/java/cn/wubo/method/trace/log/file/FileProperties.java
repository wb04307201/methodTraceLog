package cn.wubo.method.trace.log.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "method-trace-log.file")
public class FileProperties {

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
}
