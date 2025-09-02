package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ServiceCallInfo implements Cloneable {
    private String traceid;
    private String pspanid;
    private String spanid;
    private String className;
    private String methodSignature;
    private Object context;
    private LogActionEnum logActionEnum;
    private Long timeMillis;

    public ServiceCallInfo clone() {
        try {
            return (ServiceCallInfo) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

}
