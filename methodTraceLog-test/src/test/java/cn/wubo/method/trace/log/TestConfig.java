package cn.wubo.method.trace.log;

import cn.wubo.entity.sql.TestComponent;
import cn.wubo.entity.sql.TestController;
import cn.wubo.entity.sql.TestService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestConfig {

    @Bean
    public ICallService customLogService() {
        return new CustomLogServiceImpl();
    }

    @Bean
    public TestComponent testComponent() {
        return new TestComponent();
    }

    @Bean
    public TestService testService(TestComponent testComponent) {
        return new TestService(testComponent);
    }

    @Bean
    public TestController testController(TestService testService) {
        return new TestController(testService);
    }

}


