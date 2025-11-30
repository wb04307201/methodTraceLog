package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.AbstractCallService;
import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.wubo.method.trace.log.Constants.*;

@Slf4j
public class SimpleMonitorServiceImpl extends AbstractCallService {

    private final MeterRegistry meterRegistry;

    public SimpleMonitorServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private Map<String, Timer.Sample> timerSamples = new ConcurrentHashMap<>();

    @Getter
    private List<MethodTraceInfo> methodTraceInfos = new ArrayList<>();

    private Map<String, MethodTraceInfo> methodTraceInfoMap = new ConcurrentHashMap<>();

    private static final long MAX_LOG_AGE_MILLIS = 8 * 60 * 60 * 1000L; // 8小时

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.BEFORE) {
            Timer.Sample sample = Timer.start(meterRegistry);
            timerSamples.put(serviceCallInfo.getSpanid(), sample);

            MethodTraceInfo methodTraceInfo = MethodTraceInfo.create(serviceCallInfo);
            methodTraceInfoMap.put(serviceCallInfo.getSpanid(), methodTraceInfo);
            if (serviceCallInfo.getPspanid() == null) {
                cleanupExpiredEntries();
                methodTraceInfos.add(methodTraceInfo);
            } else if (methodTraceInfoMap.containsKey(serviceCallInfo.getPspanid()))
                methodTraceInfoMap.get(serviceCallInfo.getPspanid()).addChild(methodTraceInfo);
        } else if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_RETURN || serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW) {
            timerSamples.get(serviceCallInfo.getSpanid()).stop(Timer.builder(METHOD_EXECUTION_TIME).tags(CLASS_NAME, serviceCallInfo.getClassName(), METHOD_SIGNATURE, serviceCallInfo.getMethodSignatureLongString(), ACTION, serviceCallInfo.getLogActionEnum().name()).register(meterRegistry));

            if (methodTraceInfoMap.containsKey(serviceCallInfo.getSpanid())) {
                MethodTraceInfo methodTraceInfo = methodTraceInfoMap.get(serviceCallInfo.getSpanid());
                methodTraceInfo.end(serviceCallInfo);
                methodTraceInfoMap.remove(serviceCallInfo.getSpanid());
            }
        }
    }

    private void cleanupExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        methodTraceInfos.removeIf(info -> {
            if (info == null || info.getBefore() == null) {
                return true;
            }
            return currentTime - info.getBefore().getTimeMillis() >= MAX_LOG_AGE_MILLIS;
        });
    }


    @Override
    public String getCallServiceName() {
        return "SimpleMonitorService";
    }

    @Override
    public String getCallServiceDesc() {
        return "监控指标";
    }

    public MethodTraceInfo getByTraceId(String id) {
        return methodTraceInfos.stream()
                .filter(m -> m.getBefore().getTraceid().equals(id))
                .findAny()
                .orElse(null);
    }

}
