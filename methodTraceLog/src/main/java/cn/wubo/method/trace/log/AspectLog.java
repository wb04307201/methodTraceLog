package cn.wubo.method.trace.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要追踪的单个方法。被注解的方法即使所在类没有 {@code @Component / @Service / @RestController}
 * 也会被 {@link LogAspect} 拦截。可以用 {@code value} 自定义显示名。
 * <p>
 * 典型用法：
 * <pre>
 * {@code
 * public class MyHelper {
 *     @AspectLog("do-something")
 *     public void doSomething(String s) { ... }
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AspectLog {

    /**
     * 自定义显示名（出现在 trace 列表 / OTel span name 中）。留空使用 method signature。
     *
     * @return 显示名；空字符串表示使用原始方法签名
     */
    String value() default "";

    /**
     * 标签。会作为 {@code mtl.tag.<key>=<value>} 写到 trace 上下文中。
     *
     * @return key/value 交替排列的字符串数组（{@code ["k1","v1","k2","v2"]}）；空数组表示无标签
     */
    String[] tags() default {};
}
