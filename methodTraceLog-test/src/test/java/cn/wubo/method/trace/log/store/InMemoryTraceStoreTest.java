package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryTraceStoreTest {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1", "TestClass", "TestClass", "m", "m()", "m()", List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void save_and_getByTraceId() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MethodTraceInfo root = MethodTraceInfo.create(newBefore("t-1"));
        store.save(root);
        assertSame(root, store.getByTraceId("t-1"));
    }

    @Test
    void getByTraceId_unknown_returnsNull() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        assertNull(store.getByTraceId("nope"));
    }

    @Test
    void save_overwritesExisting() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MethodTraceInfo r1 = MethodTraceInfo.create(newBefore("t-1"));
        store.save(r1);
        MethodTraceInfo r2 = MethodTraceInfo.create(newBefore("t-1"));
        store.save(r2);
        assertSame(r2, store.getByTraceId("t-1"));
        assertEquals(1, store.size());
    }

    @Test
    void getRecent_newestFirst() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        for (int i = 0; i < 5; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        List<MethodTraceInfo> recent = store.getRecent(3);
        assertEquals(3, recent.size());
        assertEquals("t-4", recent.get(0).getBefore().getTraceid());
        assertEquals("t-2", recent.get(2).getBefore().getTraceid());
    }

    @Test
    void clean_removesExpired() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MethodTraceInfo root = MethodTraceInfo.create(newBefore("old"));
        // 把时间回拨到 2 小时前
        root.getBefore().setTimeMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000L);
        store.save(root);
        store.clean(60 * 60 * 1000L); // 1 小时
        assertNull(store.getByTraceId("old"));
        assertEquals(0, store.size());
    }

    @Test
    void getRecent_zeroOrNegative_returnsEmpty() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.save(MethodTraceInfo.create(newBefore("t-1")));
        assertTrue(store.getRecent(0).isEmpty());
        assertTrue(store.getRecent(-5).isEmpty());
    }

    @Test
    void save_null_doesNothing() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.save(null);
        assertEquals(0, store.size());
    }
}
