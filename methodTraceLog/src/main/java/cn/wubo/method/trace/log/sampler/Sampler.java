package cn.wubo.method.trace.log.sampler;

/**
 * 采样器接口。
 * <p>
 * 调用 {@link #shouldStartRoot()} 决定是否开始采样一个根调用；
 * 已被采样的子链路永远采样，未被采子的子链路永远不采样（在 LogAspect 内部用 MDC 透传）。
 */
public interface Sampler {

    /**
     * 是否对一个全新（无父上下文）的根调用进行采样。
     * 子调用不应调用此方法 —— 子调用从 MDC 继承父决定。
     */
    boolean shouldStartRoot();
}
