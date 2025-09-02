package cn.wubo.method.trace.log;

public class Constants {

    private Constants() {
    }

    public static final String CLASS_NAME = "className";
    public static final String METHOD_SIGNATURE = "methodSignature";
    public static final String METHOD_EXECUTION_TIME = "method.execution.time";
    public static final String ACTION = "action";
    public static final String LOG_TEMPLATE = "traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}";

}
