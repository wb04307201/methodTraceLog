package cn.wubo.method.trace.log;

/**
 * 框架级常量：Micrometer Timer 名称、tag 键、MDC 键等。
 * <p>
 * 集中放在这里避免散落在各处的字面量重复。面板 JS 也通过
 * {@code /methodTraceLog/view/list} 接口消费同一组 tag，因此键名属于
 * 公开契约——改这里要同步改前端。
 */
public class Constants {

    private Constants() {
    }

    /** Micrometer Timer tag：被调用类的全限定名。 */
    public static final String CLASS_NAME = "className";

    /** Micrometer Timer tag：方法签名（{@code MethodSignature.toLongString()}）。 */
    public static final String METHOD_SIGNATURE = "methodSignature";

    /** Micrometer Timer 名称。{@link cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl} 按此名注册 Timer。 */
    public static final String METHOD_EXECUTION_TIME = "method.execution.time";

    /** Micrometer Timer tag：动作枚举名（{@code AFTER_RETURN} / {@code AFTER_THROW}）。 */
    public static final String ACTION = "action";

}
