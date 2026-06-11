package cn.wubo.method.trace.log;


/**
 * 方法调用事件处理接口。所有自定义的日志/监控/导出实现都应实现此接口，
 * 由 {@link CallServiceStrategy} 在启动时统一收集。
 * <p>
 * 调用顺序：{@link LogAspect#around} 拦截到方法后，先用 {@code BEFORE} 调一次
 * {@link #consumer(ServiceCallInfo)}，方法执行完再用 {@code AFTER_RETURN} 或
 * {@code AFTER_THROW} 各调一次。{@link #getEnable()} 为 {@code false} 的服务
 * 会被 strategy 跳过，可通过 {@code method-trace-log.log.service-calls} 在启动时
 * 配置，或运行时通过 {@code /methodTraceLog/view/callService} 端点切换。
 */
public interface ICallService {

    /**
     * 是否启用。strategy 会跳过返回 {@code false} 的服务。
     *
     * @return true 表示接收 consumer 调用
     */
    Boolean getEnable();

    /**
     * 设置启用状态。
     *
     * @param enable true 启用、false 跳过
     */
    void setEnable(Boolean enable);

    /**
     * 处理一次方法调用事件。
     *
     * @param serviceCallInfo 当前方法的事件信息（BEFORE / AFTER_RETURN / AFTER_THROW）
     */
    void consumer(ServiceCallInfo serviceCallInfo);

    /**
     * 服务唯一名称，用于配置和面板识别。
     *
     * @return 服务标识，建议用稳定的英文/拼音，不要本地化
     */
    String getCallServiceName();

    /**
     * 服务描述，面板上展示给用户看。
     *
     * @return 人类可读的描述，允许中文
     */
    String getCallServiceDesc();

}
