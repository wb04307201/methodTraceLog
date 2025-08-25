package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ServiceCallInfo {
    private String traceid;
    private String pspanid;
    private String spanid;
    private String classname;
    private String methodSignature;
    private Object context;
    private LogActionEnum logActionEnum;
    private Long timeMillis;

}
