package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * 一次方法调用事件的数据载体。
 * <p>
 * 一个完整调用对应 2~3 个 {@code ServiceCallInfo}：{@code BEFORE} 携 args 作为
 * {@link #context}，{@code AFTER_RETURN / AFTER_THROW} 携返回值或异常。
 * 类名、方法签名两个字段都从 {@link MethodSignature} 一次性提取，省得下游
 * 重复反射。所有 setter 来自 Lombok {@code @Data}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCallInfo {
    private String traceid;
    private String pspanid;
    private String spanid;
    private String className;
    private String classSimpleName;
    private String methodName;
    private String methodSignatureShortString;
    private String methodSignatureLongString;
    private Object context;
    private LogActionEnum logActionEnum;
    private Long timeMillis;


    /**
     * 便利构造：从 AspectJ {@link MethodSignature} 一次性提取类名 / 简单类名 / 方法名 / 短签名 / 长签名。
     *
     * @param traceid         当前 trace 的全局 id
     * @param pspanid         父 span id（根调用时为 null）
     * @param spanid          本次调用的 span id
     * @param methodSignature AspectJ 反射拿到的方法签名
     * @param context         上下文：{@code BEFORE} 时是 args，{@code AFTER_*} 时是返回值或异常
     * @param logActionEnum   事件阶段
     * @param timeMillis      事件发生时间（{@link System#currentTimeMillis()}）
     */
    public ServiceCallInfo(String traceid, String pspanid, String spanid, MethodSignature methodSignature, Object context, LogActionEnum logActionEnum, Long timeMillis) {
        this.traceid = traceid;
        this.pspanid = pspanid;
        this.spanid = spanid;
        this.context = context;
        this.logActionEnum = logActionEnum;
        this.timeMillis = timeMillis;
        Class<?> declaringClass = methodSignature.getMethod().getDeclaringClass();
        this.className = declaringClass.getName();
        this.classSimpleName = declaringClass.getSimpleName();
        this.methodName = methodSignature.getName();
        this.methodSignatureShortString = methodSignature.toShortString();
        this.methodSignatureLongString = methodSignature.toLongString();
    }

    /**
     * 浅拷贝：复制 traceid / pspanid / spanid / 类名 / 方法签名 / context / action / time。
     * 用于 BEFORE → AFTER 时复用元数据，避免在 {@link cn.wubo.method.trace.log.LogAspect}
     * 里手动重新 set 一遍。
     *
     * @param original 源对象（通常是 BEFORE 事件）
     * @return 字段完全相同的新实例
     */
    public static ServiceCallInfo copyOf(ServiceCallInfo original) {
        return new ServiceCallInfo(
                original.getTraceid(),
                original.getPspanid(),
                original.getSpanid(),
                original.getClassName(),
                original.getClassSimpleName(),
                original.getMethodName(),
                original.getMethodSignatureShortString(),
                original.getMethodSignatureLongString(),
                original.getContext(),
                original.getLogActionEnum(),
                original.getTimeMillis());
    }

}
