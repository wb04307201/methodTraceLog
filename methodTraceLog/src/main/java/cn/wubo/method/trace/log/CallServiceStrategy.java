package cn.wubo.method.trace.log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CallServiceStrategy {

    private final List<ICallService> callServices;

    public CallServiceStrategy(List<ICallService> callServices, MethodTraceLogProperties properties) {
        this.callServices = callServices;
        properties.getLog().getServiceCalls().forEach(serviceCall -> {
            for (ICallService callService : callServices) {
                if (callService.getCallServiceName().equals(serviceCall.getName())) {
                    callService.setEnable(serviceCall.getEnable());
                    break;
                }
            }
        });
    }

    public void consumer(ServiceCallInfo serviceCallInfo) {
        for (ICallService callService : callServices) {
            if (Boolean.TRUE.equals(callService.getEnable())) {
                callService.consumer(serviceCallInfo);
            }
        }
    }

    public List<Map<String, Object>> setCallServiceEnable(String name, Boolean enable) {
        for (ICallService callService : callServices) {
            if (callService.getCallServiceName().equals(name)) {
                callService.setEnable(enable);
                break;
            }
        }
        return getCallServices();
    }

    public List<Map<String, Object>> getCallServices() {
        return callServices.stream().map(callService -> {
            Map<String, Object> map = new HashMap<>();
            map.put("name", callService.getCallServiceName());
            map.put("desc", callService.getCallServiceDesc());
            map.put("enable", callService.getEnable());
            return map;
        }).toList();
    }
}
