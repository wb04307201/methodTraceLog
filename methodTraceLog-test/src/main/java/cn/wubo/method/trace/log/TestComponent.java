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

    public String hello1(String text) {
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return text+"😀";
    }

    public String hello2(String text) {
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return text+"🤣";
    }

    public String hello3(String text) {
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return text+"🥲";
    }

}
