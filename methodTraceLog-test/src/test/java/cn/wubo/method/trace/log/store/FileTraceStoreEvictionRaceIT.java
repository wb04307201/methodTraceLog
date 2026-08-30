package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FileTraceStore 在多线程并发 save 时的驱逐竞态测试。
 * <p>
 * 风险 R-09：evictIfNeeded 在快照与修改之间存在窗口，新写入的 trace 可能被错误驱逐。
 * <p>
 * 测试方案：
 *  <ul>
 *      <li>8 个线程 × 10K saves，maxTraces=1000</li>
 *      <li>每个 save 都用唯一的 traceid</li>
 *      <li>在写入过程中抓"最近 100 次写入的 traceid 集合"作为基线，
 *          然后验证驱逐完成后再去 getRecent(100000)，看是否有这 100 个 traceid 丢失</li>
 *  </ul>
 */
class FileTraceStoreEvictionRaceIT {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1",
                "TestClass", "TestClass", "m", "m()", "m()",
                List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void concurrentSave_maxTraces_evictsOldest_noFreshEntryLost(@TempDir Path dir) throws Exception {
        FileTraceStore store = new FileTraceStore(dir.toString(), 3_600_000L, 1000, false);

        int threads = 8;
        int perThread = 10_000;
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);

        AtomicReference<String> firstLostFreshId = new AtomicReference<>();
        // 在并发写的中后段采样 100 个最新写入的 traceid
        int sampleWindow = 100;
        String[] sampleIds = new String[sampleWindow];
        // 用一个 long 计数器追踪当前写入数，间接推断最新 batch 的 traceid 范围

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            for (int i = 0; i < perThread; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        String tid = "t-" + threadIdx + "-" + idx;
                        store.save(MethodTraceInfo.create(newBefore(tid)));
                    } catch (Exception e) {
                        if (firstLostFreshId.get() == null) firstLostFreshId.set("submit:" + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        // 在中途采样（写盘 IO + 驱逐已经发生过几轮）
        Thread.sleep(500);
        var recentNow = store.getRecent(sampleWindow);
        Assertions.assertTrue(recentNow.size() > 0, "中途采样必须有数据");
        for (int i = 0; i < recentNow.size() && i < sampleWindow; i++) {
            sampleIds[i] = recentNow.get(i).getBefore().getTraceid();
        }
        // 等待全部完成
        Assertions.assertTrue(done.await(120, TimeUnit.SECONDS),
                "并发写入应在 120s 内完成；first err=" + firstLostFreshId.get());
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 此时 size() 应 == maxTraces（1000）
        Assertions.assertEquals(1000, store.size(),
                "maxTraces=1000 → store 内存中应有 1000 条");

        // 采样期间在内存里的 100 个 traceid（任何时候都在 1000 条窗口内），
        // 现在必须仍能查到（驱逐必须按写入时间淘汰最旧，绝不能吞掉新写入）。
        int foundFresh = 0;
        for (String tid : sampleIds) {
            if (tid != null && store.getByTraceId(tid) != null) foundFresh++;
        }
        Assertions.assertEquals(sampleWindow, foundFresh,
                "中途采样的 " + sampleWindow + " 条 traceId 必须全部仍在 store 内（无静默驱逐）");
    }
}