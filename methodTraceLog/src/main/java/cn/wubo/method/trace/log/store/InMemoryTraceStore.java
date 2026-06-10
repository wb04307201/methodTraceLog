package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 TraceStore。
 * <p>
 * 用 {@link CopyOnWriteArrayList} 保存根节点（读多写少），用 {@link ConcurrentHashMap} 索引 traceId → 根。
 * 写入时如果 traceId 已存在则覆盖（保持最新版本），避免出现孤儿根节点。
 */
public class InMemoryTraceStore implements ITraceStore {

    private final List<MethodTraceInfo> roots = new CopyOnWriteArrayList<>();
    private final Map<String, MethodTraceInfo> traceIdIndex = new ConcurrentHashMap<>();

    @Override
    public void save(MethodTraceInfo root) {
        if (root == null || root.getBefore() == null) {
            return;
        }
        String traceid = root.getBefore().getTraceid();
        MethodTraceInfo existing = traceIdIndex.get(traceid);
        if (existing == null) {
            // 防止 save 期间并发插入两次
            synchronized (traceIdIndex) {
                existing = traceIdIndex.get(traceid);
                if (existing == null) {
                    roots.add(0, root); // 最新在前
                    traceIdIndex.put(traceid, root);
                    return;
                }
            }
        }
        // 覆盖：直接替换 roots 中的引用
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i) == existing) {
                roots.set(i, root);
                break;
            }
        }
        traceIdIndex.put(traceid, root);
    }

    @Override
    public MethodTraceInfo getByTraceId(String traceid) {
        return traceid == null ? null : traceIdIndex.get(traceid);
    }

    @Override
    public List<MethodTraceInfo> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int n = Math.min(limit, roots.size());
        return new ArrayList<>(roots.subList(0, n));
    }

    @Override
    public void clean(long maxAgeMillis) {
        if (maxAgeMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        roots.removeIf(info -> {
            if (info == null || info.getBefore() == null) {
                return true;
            }
            if (now - info.getBefore().getTimeMillis() > maxAgeMillis) {
                traceIdIndex.remove(info.getBefore().getTraceid());
                return true;
            }
            return false;
        });
    }

    @Override
    public int size() {
        return roots.size();
    }
}
