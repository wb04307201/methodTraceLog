package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.aspectj.lang.reflect.MethodSignature;

@Data
@AllArgsConstructor
public class ServiceCallInfo implements Cloneable {
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

    public ServiceCallInfo clone() {
        try {
            return (ServiceCallInfo) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

}
