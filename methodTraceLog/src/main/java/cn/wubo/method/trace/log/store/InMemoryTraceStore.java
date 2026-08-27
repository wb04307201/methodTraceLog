package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存版 TraceStore。
 * <p>
 * 用 {@link ConcurrentLinkedDeque} 保存根节点（无锁头插 + 范围删除），用 {@link ConcurrentHashMap} 索引 traceId → 根。
 * 与之前的 {@link java.util.concurrent.CopyOnWriteArrayList} 相比，
 * 写入不再需要拷贝整个底层数组，N 大时写入开销显著降低。
 * <p>
 * 写入时如果 traceId 已存在则覆盖（保持最新版本），避免出现孤儿根节点。
 */
public class InMemoryTraceStore implements ITraceStore {

    private final Deque<MethodTraceInfo> roots = new ConcurrentLinkedDeque<>();
    private final Map<String, MethodTraceInfo> traceIdIndex = new ConcurrentHashMap<>();

    @Override
    public void save(MethodTraceInfo root) {
        if (root == null || root.getBefore() == null) {
            return;
        }
        String traceid = root.getBefore().getTraceid();
        MethodTraceInfo existing = traceIdIndex.get(traceid);
        if (existing == null) {
            MethodTraceInfo prev = traceIdIndex.putIfAbsent(traceid, root);
            if (prev == null) {
                // 最新在前
                roots.addFirst(root);
                return;
            }
            existing = prev;
        }
        // 覆盖：ConcurrentLinkedDeque 没有按值定位的 API，先删除旧的再头插新的
        roots.remove(existing);
        roots.addFirst(root);
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
        List<MethodTraceInfo> out = new ArrayList<>(Math.min(limit, roots.size()));
        Iterator<MethodTraceInfo> it = roots.iterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    @Override
    public void clean(long maxAgeMillis) {
        if (maxAgeMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<MethodTraceInfo> it = roots.iterator();
        while (it.hasNext()) {
            MethodTraceInfo info = it.next();
            if (info == null || info.getBefore() == null) {
                it.remove();
                continue;
            }
            if (now - info.getBefore().getTimeMillis() > maxAgeMillis) {
                traceIdIndex.remove(info.getBefore().getTraceid());
                it.remove();
            }
        }
    }

    @Override
    public int size() {
        return roots.size();
    }
}
