package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * InMemoryTraceStore 同 traceId 并发 save 的数据竞争测试。
 * <p>
 * 风险 R-13：两个线程同时 save 同一个 traceId，{@code putIfAbsent} 与后续写入之间存在窗口；
 * 覆盖路径 {@code roots.remove(existing); roots.addFirst(root)} 也不是原子的，
 * 会导致 roots 中出现重复条目。
 * <p>
 * 本测试同时覆盖两个方向：
 *  <ul>
 *      <li><b>并发根</b>（无冲突）：每个线程写不同 traceId —— 验证 size == 写入数，
 *          与既有的 InMemoryTraceStoreConcurrencyTest 一致</li>
 *      <li><b>同 traceId 并发</b>（R-13 触发）：验证
 *          <ol>
 *              <li>{@code getByTraceId} 不抛异常（traceIdIndex 状态自洽）</li>
 *              <li>{@code getRecent(10)} 至少有一条（不会全丢）</li>
 *              <li>{@code size()} 远小于 {@code threads * writesPerThread}（即合并确实发生了，
 *                  没有完全退化为每写一条 addFirst）</li>
 *              <li>所有 entry 的 traceid 都正确（没混入其它 traceid）</li>
 *          </ol>
 *      </li>
 *  </ul>
 * <p>
 * <b>已知行为</b>：R-13 修复前，同 traceId 并发 save 可能让 {@code size()} 远大于 1。
 * 本测试断言 size &lt; totalWrites/2（合并确实生效），但允许 size &gt; 1 —— 这是当前
 * 无锁设计的实际行为，不是测试失败。若后续加锁修复 R-13，可把断言收紧到 {@code size()==1}。
 */
class InMemoryTraceStoreSameTraceIdRaceIT {

    private ServiceCallInfo newBefore(String traceid, long ts) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1",
                "TestClass", "TestClass", "m", "m()", "m()",
                List.of(1), LogActionEnum.BEFORE, ts);
    }

    @Test
    void concurrentSave_distinctTraceIds_keepsAll() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        int threads = 16;
        int writesPerThread = 100;
        int total = threads * writesPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            for (int i = 0; i < writesPerThread; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        store.save(MethodTraceInfo.create(newBefore(
                                "t-" + threadIdx + "-" + idx, System.currentTimeMillis())));
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        Assertions.assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        Assertions.assertEquals(total, store.size(),
                "不同 traceId 并发写入不应合并；expected size=" + total);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < writesPerThread; i++) {
                Assertions.assertNotNull(store.getByTraceId("t-" + t + "-" + i),
                        "并发写不同 traceId 不能丢失；missing t-" + t + "-" + i);
            }
        }
    }

    @Test
    void concurrentSave_sameTraceId_doesNotThrow_andMergesEffectively() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        String tid = "shared-trace";
        int threads = 16;
        int writesPerThread = 100;
        int total = threads * writesPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        store.save(MethodTraceInfo.create(newBefore(tid,
                                System.currentTimeMillis() + threadIdx * writesPerThread + i)));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        Assertions.assertTrue(done.await(20, TimeUnit.SECONDS),
                "并发 same-traceId writes 应在 20s 内完成");
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // 1. 不抛：traceIdIndex 应有当前 traceId
        MethodTraceInfo winner = store.getByTraceId(tid);
        Assertions.assertNotNull(winner, "getByTraceId 必须能找到共享 trace");
        Assertions.assertEquals(tid, winner.getBefore().getTraceid());

        // 2. 合并确实生效：size 远小于 total（否则意味着 addFirst 每次都加成功）
        int finalSize = store.size();
        Assertions.assertTrue(finalSize < total / 2,
                "合并失败：size(" + finalSize + ") 应小于 total/2(" + (total / 2) + ")");

        // 3. getRecent 不抛，且条目 traceId 都对
        List<MethodTraceInfo> recent = store.getRecent(100);
        Assertions.assertFalse(recent.isEmpty(), "getRecent 必须非空");
        for (MethodTraceInfo info : recent) {
            Assertions.assertEquals(tid, info.getBefore().getTraceid(),
                    "getRecent 混入其它 traceId: " + info.getBefore().getTraceid());
        }

        // 4. 间接验证 roots 与 traceIdIndex 状态自洽：
        //    roots 里的 traceId 都应是 tid
        Deque<?> rootsRef = (Deque<?>) extractRoots(store);
        Assertions.assertNotNull(rootsRef);
        for (Object o : rootsRef) {
            MethodTraceInfo info = (MethodTraceInfo) o;
            Assertions.assertEquals(tid, info.getBefore().getTraceid());
        }
    }

    /** 反射拿 roots 字段（仅测试用） */
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedDeque<MethodTraceInfo> extractRoots(InMemoryTraceStore store) throws Exception {
        java.lang.reflect.Field f = InMemoryTraceStore.class.getDeclaredField("roots");
        f.setAccessible(true);
        return (ConcurrentLinkedDeque<MethodTraceInfo>) f.get(store);
    }
}