package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;

import java.util.List;

/**
 * trace 持久化接口。
 * <p>
 * 一个根 {@link MethodTraceInfo} 树在 BEFORE 事件到达时被视为"未完成"，
 * 任意 AFTER_* 事件到达时被视为"已完成"（{@code after} 字段非 null）。
 * 实现应当只保存已完成或可读出部分结构的根节点；未完成的根节点可以临时缓存。
 */
public interface ITraceStore {

    /**
     * 将根 trace 放入存储。如果 traceId 已存在，旧记录将被覆盖。
     *
     * @param root 根节点（{@code before} 必有、{@code after} 可能为 null）
     */
    void save(MethodTraceInfo root);

    /**
     * 按 traceId 查询。
     *
     * @param traceid 要查找的根 trace id
     * @return 根节点，找不到返回 null
     */
    MethodTraceInfo getByTraceId(String traceid);

    /**
     * 最近完成的根 trace 列表（新的在前），最多返回 limit 条。
     *
     * @param limit 上限条数
     * @return 根节点列表，按完成时间倒序；可能为空但不会为 null
     */
    List<MethodTraceInfo> getRecent(int limit);

    /**
     * 清理超过 maxAgeMillis 的根 trace。实现应同时释放索引与底层数据。
     *
     * @param maxAgeMillis 过期阈值（毫秒）
     */
    void clean(long maxAgeMillis);

    /**
     * 当前存储中根 trace 的数量。
     *
     * @return 根 trace 条数
     */
    int size();
}
