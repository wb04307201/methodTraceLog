package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoOpTraceStoreTest {

    @Test
    void allOperationsAreNoOps() {
        NoOpTraceStore store = NoOpTraceStore.INSTANCE;
        MethodTraceInfo root = MethodTraceInfo.create(new ServiceCallInfo("t", null, "s", "C", "C", "m", "m()", "m()", List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis()));
        store.save(root);
        assertNull(store.getByTraceId("t"));
        assertTrue(store.getRecent(10).isEmpty());
        store.clean(1000L);
        assertEquals(0, store.size());
    }
}
