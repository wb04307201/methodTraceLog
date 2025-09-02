package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.wubo.method.trace.log.Constants.*;

@Slf4j
public class SimpleMonitorServiceImpl implements ICallService {

    private final MeterRegistry meterRegistry;

    public SimpleMonitorServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private Map<String, Timer.Sample> timerSamples = new ConcurrentHashMap<>();

    @Getter
    private List<MethodTraceInfo> methodTraceInfos = new LinkedList<>();

    private Map<String, MethodTraceInfo> methodTraceInfoMap = new ConcurrentHashMap<>();

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.BEFORE) {
            Timer.Sample sample = Timer.start(meterRegistry);
            timerSamples.put(serviceCallInfo.getSpanid(), sample);

            MethodTraceInfo methodTraceInfo = MethodTraceInfo.create(serviceCallInfo);
            methodTraceInfoMap.put(serviceCallInfo.getSpanid(), methodTraceInfo);
            if (serviceCallInfo.getPspanid() == null) {
                if (methodTraceInfos.size() > 100) methodTraceInfos.remove(0);
                methodTraceInfos.add(methodTraceInfo);
            } else if (methodTraceInfoMap.containsKey(serviceCallInfo.getPspanid()))
                methodTraceInfoMap.get(serviceCallInfo.getPspanid()).addChild(methodTraceInfo);
        } else if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_RETURN || serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW) {
            timerSamples.get(serviceCallInfo.getSpanid()).stop(Timer.builder(METHOD_EXECUTION_TIME).tags(CLASS_NAME, serviceCallInfo.getClassName(), METHOD_SIGNATURE, serviceCallInfo.getMethodSignature(), ACTION, serviceCallInfo.getLogActionEnum().name()).register(meterRegistry));

            if (methodTraceInfoMap.containsKey(serviceCallInfo.getSpanid())) {
                MethodTraceInfo methodTraceInfo = methodTraceInfoMap.get(serviceCallInfo.getSpanid());
                methodTraceInfo.end(serviceCallInfo);
                methodTraceInfoMap.remove(serviceCallInfo.getSpanid());
            }

        }
    }

    public MethodTraceInfo getByTraceId(String id) {
        return methodTraceInfos.stream()
                .filter(m -> m.getBefore().getTraceid().equals(id))
                .findAny()
                .orElse(null);
    }

}
