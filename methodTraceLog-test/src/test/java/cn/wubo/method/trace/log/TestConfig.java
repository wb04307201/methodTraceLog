package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.service.ILogService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestConfig {

    @Bean
    public ILogService logService() {
        return new CustomLogServiceImpl();
    }

    @Bean
    public TestComponent testComponent() {
        return new TestComponent();
    }

    @Bean
    public TestService testService() {
        return new TestService();
    }

    @Bean
    public TestController testController() {
        return new TestController();
    }

}


