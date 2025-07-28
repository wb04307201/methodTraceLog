package cn.wubo.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "log")
public class LogProperties {

    private String logService = "cn.wubo.log.core.SimpleeLogServiceImpl";

}
