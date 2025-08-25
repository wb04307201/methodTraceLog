package cn.wubo.entity.sql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TestService {

    private final TestComponent testComponent;

    @Autowired
    public TestService(TestComponent testComponent) {
        this.testComponent = testComponent;
    }

    public String hello(String name) {
        return testComponent.hello(name);
    }
}
