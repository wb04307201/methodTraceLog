package cn.wubo.entity.sql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static java.lang.Thread.sleep;

@Service
public class TestService {

    private final TestComponent testComponent;

    @Autowired
    public TestService(TestComponent testComponent) {
        this.testComponent = testComponent;
    }

    public String hello(String name) {
        try {
            sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return testComponent.hello(name);
    }
}
