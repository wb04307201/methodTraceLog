package cn.wubo.log.core;

public interface ILogService {

    void log(String traceid, String pspanid, String spanid, String classname, String methodSignature, Object context, LogActionEnum logActionEnum);

}
