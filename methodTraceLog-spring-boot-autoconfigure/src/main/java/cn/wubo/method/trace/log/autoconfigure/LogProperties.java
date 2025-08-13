package cn.wubo.method.trace.log.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "method-trace-log")
public class LogProperties {

    private Boolean enable = true;

}
