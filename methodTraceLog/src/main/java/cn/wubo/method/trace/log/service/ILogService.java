package cn.wubo.method.trace.log.service;

import cn.wubo.method.trace.log.LogActionEnum;

public interface ILogService {

    void log(String traceid, String pspanid, String spanid, String classname, String methodSignature, Object context, LogActionEnum logActionEnum);

}
