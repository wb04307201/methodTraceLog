package cn.wubo.method.trace.log;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static java.lang.Thread.sleep;

@Component
public class TestComponent {

    public String hello(String name) {
        Random random = new Random();
        int value = random.nextInt(2) + 1;
        if (value == 1) {
            throw new RuntimeException("测试异常");
        }

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
        return text + "😀";
    }

    public String hello2(String text) {
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return text + "🤣";
    }

    public String hello3(String text) {
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return text + "🥲";
    }

    /**
     * 演示 @AspectLog：方法名在 trace 中显示为 "aspectLogDemo" 而非 "realImplMethod"。
     */
    @AspectLog("aspectLogDemo")
    public String aspectLogDemo(String name) {
        return "hello " + name;
    }

    /**
     * 真实方法名是 internalImplMethod，但 trace 中显示为 "renamedInTrace"。
     * 调用方完全无感知 —— 走 AOP 重写 methodName。
     */
    @AspectLog("renamedInTrace")
    public String internalImplMethod(String name) {
        return "internal-" + name;
    }

    /**
     * 真实方法名 internalImplMethodThrowing，但 trace 中显示为 "renamedThrowing"。
     * 抛异常可触发 AlertingService 告警，验证告警里的 methodName 是重命名后的值。
     */
    @AspectLog("renamedThrowing")
    public String internalImplMethodThrowing(String name) {
        throw new RuntimeException("renamedThrowing 自爆：" + name);
    }

}
