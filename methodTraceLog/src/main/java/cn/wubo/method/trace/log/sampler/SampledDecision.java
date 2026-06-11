package cn.wubo.method.trace.log.sampler;

/**
 * 表示一次跟踪是否被采样。
 * <p>
 * 根调用由 Sampler 决定；子调用必须继承父调用的决定（被采样 / 未被采样），
 * 整条链路保持一致。
 */
public enum SampledDecision {
    /** 跟踪被采样：所有 ICallService 都会处理此事件。 */
    SAMPLED,
    /** 跟踪未被采样：所有 ICallService 都会被跳过，零开销。 */
    NOT_SAMPLED
}
