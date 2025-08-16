package cn.wubo.entity.sql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TestService {

    @Autowired
    private TestComponent testComponent;

    public String hello(String name) {
        return testComponent.hello(name);
    }
}
