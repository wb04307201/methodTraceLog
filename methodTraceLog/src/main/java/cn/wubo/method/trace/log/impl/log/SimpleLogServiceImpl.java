package cn.wubo.method.trace.log.impl.log;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认日志服务：把每次方法调用打成一行结构化日志。
 * <p>
 * 异常事件用 ERROR 级别，其余用 INFO。{@link #consumer} 里再次走
 * {@link AbstractCallService#transContext} 是幂等的（{@code String / List / 基本类型} 走 else 原样返回），
 * 这里保留是为了在 {@code LogAspect} 之外的调用方手工调用时也能拿到净化结果。
 */
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
