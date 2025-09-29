package cn.wubo.method.trace.log;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.sleep;

@Component
public class TestComponent {

    public String hello(String name) {
        try {
            sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return String.format("%S say:'hello world!'", name);
    }

}
