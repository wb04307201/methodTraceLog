package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.wubo.method.trace.log.Constants.*;

@Slf4j
public class SimpleMonitorServiceImpl implements ICallService {

    private final MeterRegistry meterRegistry;

    public SimpleMonitorServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private static Map<String, Timer.Sample> timerSamples = new ConcurrentHashMap<>();

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.BEFORE){
            meterRegistry.counter(METHOD_CALLS_TOTAL, CLASS_NAME, serviceCallInfo.getClassname(), METHOD_SIGNATURE, serviceCallInfo.getMethodSignature()).increment();
            Timer.Sample sample = Timer.start(meterRegistry);
            timerSamples.put(serviceCallInfo.getSpanid(), sample);
        }else if(serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_RETURN){
            meterRegistry.counter(METHOD_CALLS_SUCCESS, CLASS_NAME, serviceCallInfo.getClassname(), METHOD_SIGNATURE, serviceCallInfo.getMethodSignature()).increment();
            timerSamples.get(serviceCallInfo.getSpanid()).stop(Timer.builder(METHOD_EXECUTION_TIME).tags(CLASS_NAME, serviceCallInfo.getClassname(),METHOD_SIGNATURE, serviceCallInfo.getMethodSignature(),STATUS,"success").register(meterRegistry));
        }else if(serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW){
            meterRegistry.counter(METHOD_CALLS_FAILURE, CLASS_NAME, serviceCallInfo.getClassname(), METHOD_SIGNATURE, serviceCallInfo.getMethodSignature()).increment();
            timerSamples.get(serviceCallInfo.getSpanid()).stop(Timer.builder(METHOD_EXECUTION_TIME).tags(CLASS_NAME, serviceCallInfo.getClassname(),METHOD_SIGNATURE, serviceCallInfo.getMethodSignature(),STATUS,"exception").register(meterRegistry));
        }
    }
}
