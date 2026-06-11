package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;

import java.util.List;

/**
 * 不存储任何 trace 的空实现。Micrometer 指标仍然写入。
 * 适用于"我只想要指标，不需要 trace 列表"的场景。
 */
public class NoOpTraceStore implements ITraceStore {

    /**
     * 单例：无状态实现，调用方可以直接拿这个共享实例避免重复创建。
     */
    public static final NoOpTraceStore INSTANCE = new NoOpTraceStore();

    @Override
    public void save(MethodTraceInfo root) {
        // no-op
    }

    @Override
    public MethodTraceInfo getByTraceId(String traceid) {
        return null;
    }

    @Override
    public List<MethodTraceInfo> getRecent(int limit) {
        return List.of();
    }

    @Override
    public void clean(long maxAgeMillis) {
        // no-op
    }

    @Override
    public int size() {
        return 0;
    }
}
