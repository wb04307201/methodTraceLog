package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class InMemoryTraceStoreConcurrencyTest {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1", "TestClass", "TestClass", "m", "m()", "m()", List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    private MethodTraceInfo newTrace(String traceid) {
        return MethodTraceInfo.create(newBefore(traceid));
    }

    @Test
    void concurrent_save_does_not_lose_traces() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        int n = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    store.save(newTrace("trace-" + id));
                } finally {
                    latch.countDown();
                }
            });
        }
        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), "all saves should complete in time");
        pool.shutdown();
        Assertions.assertEquals(n, store.size());
    }

    @Test
    void clean_removes_old_traces() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MethodTraceInfo old = newTrace("old");
        old.getBefore().setTimeMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000L);
        store.save(old);
        MethodTraceInfo fresh = newTrace("fresh");
        store.save(fresh);
        store.clean(60 * 60 * 1000L); // 1 小时
        Assertions.assertNull(store.getByTraceId("old"));
        Assertions.assertNotNull(store.getByTraceId("fresh"));
        Assertions.assertEquals(1, store.size());
    }
}
