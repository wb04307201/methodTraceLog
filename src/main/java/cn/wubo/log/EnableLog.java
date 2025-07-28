package cn.wubo.log;

import cn.wubo.log.config.LogConfig;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import({LogConfig.class})
public @interface EnableLog {
}
