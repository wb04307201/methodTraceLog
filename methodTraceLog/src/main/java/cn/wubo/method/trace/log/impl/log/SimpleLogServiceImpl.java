package cn.wubo.method.trace.log.impl.log;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimpleLogServiceImpl extends AbstractCallService {

    private static final String LOG_TEMPLATE = "traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}";

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        // LogAspect 已经在写入 ServiceCallInfo.context 前做了 transContext 净化；
        // 这里再调一次是幂等的（String/List/基本类型走 else 分支原样返回），保留作为防御性兜底。
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW)
            log.error(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignatureLongString(), AbstractCallService.transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
        else
            log.info(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignatureLongString(), AbstractCallService.transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
    }

    @Override
    public String getCallServiceName() {
        return "SimpleLogService";
    }

    @Override
    public String getCallServiceDesc() {
        return "日志输出";
    }
}
