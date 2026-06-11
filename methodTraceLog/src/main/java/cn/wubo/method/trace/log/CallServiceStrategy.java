package cn.wubo.method.trace.log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集所有 {@link ICallService} Bean 并对外提供统一的 fan-out 入口。
 * <p>
 * Spring 启动时把 {@code List<ICallService>} 注入进来；构造方法中按
 * {@link MethodTraceLogProperties.LogProperties#serviceCalls} 配置的
 * 初始 enable 标志覆盖默认状态。运行时可通过 {@link #setCallServiceEnable(String, Boolean)}
 * 切换单个服务的启用位。
 */
public class CallServiceStrategy {

    private final List<ICallService> callServices;

    /**
     * 构造方法：注入全部 ICallService Bean 并按配置覆盖 enable 标志。
     *
     * @param callServices Spring 注入的全部 ICallService Bean
     * @param properties   启动配置（其中 {@link MethodTraceLogProperties.LogProperties#serviceCalls} 提供初始 enable）
     */
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

    /**
     * 把事件分发给所有启用的 ICallService。禁用的服务会被静默跳过。
     *
     * @param serviceCallInfo 当前方法事件（BEFORE / AFTER_RETURN / AFTER_THROW）
     */
    public void consumer(ServiceCallInfo serviceCallInfo) {
        for (ICallService callService : callServices) {
            if (Boolean.TRUE.equals(callService.getEnable())) {
                callService.consumer(serviceCallInfo);
            }
        }
    }

    /**
     * 运行时切换指定服务的 enable 标志，并返回切换后全部服务的状态。
     *
     * @param name   目标服务名（{@link ICallService#getCallServiceName()}）
     * @param enable 新的 enable 值
     * @return 切换后全部服务的状态列表，结构同 {@link #getCallServices()}
     */
    public List<Map<String, Object>> setCallServiceEnable(String name, Boolean enable) {
        for (ICallService callService : callServices) {
            if (callService.getCallServiceName().equals(name)) {
                callService.setEnable(enable);
                break;
            }
        }
        return getCallServices();
    }

    /**
     * 列出全部 ICallService 及其当前 enable 状态。供面板的"服务开关"区渲染使用。
     *
     * @return 服务列表，每项包含 {@code name} / {@code desc} / {@code enable} 三个键
     */
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
