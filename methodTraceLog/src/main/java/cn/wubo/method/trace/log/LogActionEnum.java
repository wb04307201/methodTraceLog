package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 方法调用事件的三个阶段。BEFORE 是切入点之前，AFTER_RETURN 是正常返回之后，
 * AFTER_THROW 是异常抛出之后。{@link LogAspect} 会按这个顺序给每个被拦截的方法
 * 发出 2~3 次事件。
 */
@ToString
@AllArgsConstructor
public enum LogActionEnum {

    /** 方法执行前，{@code jp.proceed()} 之前。 */
    BEFORE("方法执行前"),

    /** 方法正常返回之后，{@code jp.proceed()} 返回值已经拿到。 */
    AFTER_RETURN("方法执行后"),

    /** 方法抛异常之后，异常对象还没传给调用方。 */
    AFTER_THROW("方法抛出异常");

    /** 中文描述，面板/日志展示用。 */
    @Getter
    private final String desc;

}
