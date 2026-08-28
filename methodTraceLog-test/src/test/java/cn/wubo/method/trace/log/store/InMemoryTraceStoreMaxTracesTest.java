package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 InMemoryTraceStore 在 maxTraces 配置生效后会真正淘汰最旧条目。
 * <p>
 * 修复前：maxTraces 参数从未传入，store 不受上限控制，roots.size() 可以无限增长。
 * 修复后：构造时传入 maxTraces，save() 后超过上限会从队尾淘汰并清理 traceIdIndex。
 */
class InMemoryTraceStoreMaxTracesTest {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1", "TestClass", "TestClass", "m", "m()", "m()", List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void save_moreThanMax_evictsOldestFromGetRecent() {
        InMemoryTraceStore store = new InMemoryTraceStore(3);
        for (int i = 0; i < 5; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }

        // 内存中只保留最新的 3 条
        assertEquals(3, store.size());
        List<MethodTraceInfo> recent = store.getRecent(10);
        assertEquals(3, recent.size());

        // 最新的三个仍可查
        assertNotNull(store.getByTraceId("t-4"));
        assertNotNull(store.getByTraceId("t-3"));
        assertNotNull(store.getByTraceId("t-2"));

        // 最旧的两个已被淘汰
        assertNull(store.getByTraceId("t-0"));
        assertNull(store.getByTraceId("t-1"));
    }

    @Test
    void save_withinMax_doesNotEvict() {
        InMemoryTraceStore store = new InMemoryTraceStore(5);
        for (int i = 0; i < 3; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        assertEquals(3, store.size());
        for (int i = 0; i < 3; i++) {
            assertNotNull(store.getByTraceId("t-" + i));
        }
    }

    @Test
    void zeroMaxMeansUnlimited() {
        InMemoryTraceStore store = new InMemoryTraceStore(0);
        for (int i = 0; i < 100; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        assertEquals(100, store.size());
    }

    @Test
    void noArgConstructor_doesNotEnforceLimit() {
        // 兼容旧用法：无参构造 = 不限制
        InMemoryTraceStore store = new InMemoryTraceStore();
        for (int i = 0; i < 10; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        assertEquals(10, store.size());
    }

    @Test
    void eviction_keepsNewestFirst() {
        InMemoryTraceStore store = new InMemoryTraceStore(2);
        store.save(MethodTraceInfo.create(newBefore("a")));
        store.save(MethodTraceInfo.create(newBefore("b")));
        store.save(MethodTraceInfo.create(newBefore("c")));
        List<MethodTraceInfo> recent = store.getRecent(10);
        // 最新的 c 在前，然后是 b
        assertEquals("c", recent.get(0).getBefore().getTraceid());
        assertEquals("b", recent.get(1).getBefore().getTraceid());
    }
}
