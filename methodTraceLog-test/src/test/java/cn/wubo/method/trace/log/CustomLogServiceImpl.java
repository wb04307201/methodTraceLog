package cn.wubo.method.trace.log;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CustomLogServiceImpl implements ICallService {

    public static final String LOG_TEMPLATE = "custom-log traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}";

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW)
            log.error(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassname(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
        else
            log.info(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassname(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
    }
}
