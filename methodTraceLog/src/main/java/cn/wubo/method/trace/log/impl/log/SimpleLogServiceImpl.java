package cn.wubo.method.trace.log.impl.log;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.extern.slf4j.Slf4j;

import static cn.wubo.method.trace.log.Constants.LOG_TEMPLATE;

@Slf4j
public class SimpleLogServiceImpl implements ICallService {

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW)
            log.error(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
        else
            log.info(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
    }
}
