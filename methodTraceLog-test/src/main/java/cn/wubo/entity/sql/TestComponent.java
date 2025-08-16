package cn.wubo.entity.sql;

import org.springframework.stereotype.Component;

@Component
public class TestComponent {

    public String hello(String name) {
        return String.format("%S say:'hello world!'", name);
    }

}
