package cn.wubo.method.trace.log;

import lombok.Data;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 用于验证 blacklist 功能 — Lombok @Data 自动生成 equals/hashCode/toString。
 * 这些方法都是用户定义的方法（不是 Object 基类方法），能被 AOP 拦截。
 *
 * 必须注册为 Spring 组件 (@Component)：CGLIB 只会代理 Spring 容器里的 bean，
 * 所以调用方拿到的是注入进来的代理引用（不能直接 new）。
 *
 * 用 prototype scope：每次 @Autowired 注入都拿一个新的实例（CGLIB 代理），
 * 这样 {@code /test/blacklist} 端点里的 a / b 是两个独立 bean，
 * equals/hashCode/toString 才有"两个不同对象"的语义。
 */
@Data
@Component
@Scope("prototype")
public class TestLombokEntity {
    private String name;
    private int value;

    /** 用户自定义的方法 — 应该被 AOP 拦截并可被 exclude-patterns 排除 */
    public String describe() {
        return "name=" + name + ", value=" + value;
    }

    /** 用户自定义方法 — 不在排除列表，应该出现在 trace */
    public String doWork() {
        return "doing work on " + name;
    }
}