package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次方法调用的"前/后 + 子调用"快照。
 * <p>
 * {@link #before} 由 {@link cn.wubo.method.trace.log.LogAspect} 在方法执行前写入；
 * {@link #after} 由同一调用在 {@code AFTER_RETURN / AFTER_THROW} 时填充；
 * {@link #children} 在子调用 BEFORE 时由父节点 {@link #addChild(MethodTraceInfo)} 接入。
 * <p>
 * 通过 {@link #create} 工厂创建，{@link #end} 标记完成，{@link #addChild} 挂子节点。
 * 完整节点会被 {@link SimpleMonitorServiceImpl} 持久化到 {@code ITraceStore}。
 */
@Data
public class MethodTraceInfo {

    private ServiceCallInfo before;
    private ServiceCallInfo after;

    private List<MethodTraceInfo> children = new ArrayList<>();


    /**
     * 从 BEFORE 事件创建一棵新根节点。
     *
     * @param before BEFORE 阶段的事件快照
     * @return 新创建的节点，{@code after=null}、{@code children=空列表}
     */
    public static MethodTraceInfo create(ServiceCallInfo before) {
        MethodTraceInfo methodTraceInfo = new MethodTraceInfo();
        methodTraceInfo.setBefore(before);
        return methodTraceInfo;
    }

    /**
     * 标记调用完成。{@code after} 通常是 {@code AFTER_RETURN} 或 {@code AFTER_THROW} 事件。
     *
     * @param after AFTER 阶段的事件快照
     */
    public void end(ServiceCallInfo after) {
        this.setAfter(after);
    }

    /**
     * 挂一个子节点到 {@link #children} 列表末尾。
     *
     * @param child 子调用对应的节点
     */
    public void addChild(MethodTraceInfo child) {
        children.add(child);
    }
}
