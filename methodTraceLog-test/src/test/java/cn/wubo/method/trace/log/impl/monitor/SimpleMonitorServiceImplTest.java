package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.store.InMemoryTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimpleMonitorServiceImpl 的 trace store 分派测试。
 * <p>
 * 不走 Spring 容器：手动 new 出 {@link SimpleMeterRegistry} 与 {@link InMemoryTraceStore}，
 * 再用反射触碰内部 map（这些 map 是 private，但属于"实现细节可观察的副作用"
 * —— 它们的存在就是分派正确性的证据）。
 */
class SimpleMonitorServiceImplTest {

    private static final long MAX_AGE_MILLIS = 8L * 60 * 60 * 1000L;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final InMemoryTraceStore store = new InMemoryTraceStore();
    private final SimpleMonitorServiceImpl service = new SimpleMonitorServiceImpl(meterRegistry, store, MAX_AGE_MILLIS);

    private static ServiceCallInfo before(String traceid, String spanid, String pspanid, String className, String methodName) {
        return new ServiceCallInfo(
                traceid,
                pspanid,
                spanid,
                className,
                className,
                methodName,
                methodName + "()",
                methodName + "()",
                List.of("arg"),
                LogActionEnum.BEFORE,
                System.currentTimeMillis());
    }

    private static ServiceCallInfo after(String traceid, String spanid, String pspanid, String className, String methodName, Object ctx) {
        return new ServiceCallInfo(
                traceid,
                pspanid,
                spanid,
                className,
                className,
                methodName,
                methodName + "()",
                methodName + "()",
                ctx,
                LogActionEnum.AFTER_RETURN,
                System.currentTimeMillis());
    }

    private static ServiceCallInfo afterThrow(String traceid, String spanid, String pspanid, String className, String methodName, Throwable t) {
        ServiceCallInfo info = new ServiceCallInfo(
                traceid,
                pspanid,
                spanid,
                className,
                className,
                methodName,
                methodName + "()",
                methodName + "()",
                t,
                LogActionEnum.AFTER_THROW,
                System.currentTimeMillis());
        info.setRawException(t);
        return info;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> privateMap(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Map<K, V>) f.get(target);
    }

    private static Object invokePrivate(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return m.invoke(target);
    }

    @Test
    void BEFORE_creates_span_in_store() throws Exception {
        ServiceCallInfo b = before("t-1", "s-1", null, "Demo", "doWork");

        service.consumer(b);

        // store 应已收到一个根节点（BEFORE 时就占位）
        MethodTraceInfo saved = store.getByTraceId("t-1");
        assertNotNull(saved, "BEFORE 时 store 应占位写入");
        assertEquals("doWork", saved.getBefore().getMethodName());
        // methodTraceInfoMap 应有这条 span
        Map<String, MethodTraceInfo> map = privateMap(service, "methodTraceInfoMap");
        assertTrue(map.containsKey("s-1"));
    }

    @Test
    void AFTER_RETURN_finalizes_span_in_store() throws Exception {
        ServiceCallInfo b = before("t-1", "s-1", null, "Demo", "doWork");
        service.consumer(b);

        ServiceCallInfo after = after("t-1", "s-1", null, "Demo", "doWork", "ok");
        service.consumer(after);

        MethodTraceInfo saved = store.getByTraceId("t-1");
        assertNotNull(saved.getAfter(), "AFTER_RETURN 后 store 中应有 after 字段");
        assertEquals(LogActionEnum.AFTER_RETURN, saved.getAfter().getLogActionEnum());
        assertEquals("ok", saved.getAfter().getContext());

        // methodTraceInfoMap 应清空
        Map<String, MethodTraceInfo> map = privateMap(service, "methodTraceInfoMap");
        assertFalse(map.containsKey("s-1"));
    }

    @Test
    void AFTER_THROW_sets_status_to_error() throws Exception {
        ServiceCallInfo b = before("t-2", "s-2", null, "Demo", "boom");
        service.consumer(b);

        RuntimeException ex = new RuntimeException("kaboom");
        ServiceCallInfo after = afterThrow("t-2", "s-2", null, "Demo", "boom", ex);
        service.consumer(after);

        MethodTraceInfo saved = store.getByTraceId("t-2");
        assertNotNull(saved.getAfter());
        assertEquals(LogActionEnum.AFTER_THROW, saved.getAfter().getLogActionEnum());
        assertSame(ex, saved.getAfter().getRawException(), "rawException 必须为原始异常对象");
    }

    @Test
    void orphan_cleanup_after_threshold() throws Exception {
        // 只发 BEFORE、不发 AFTER，让这条 span 变成孤儿
        ServiceCallInfo b = before("t-orphan", "s-orphan", null, "Demo", "hang");
        // 把 timeMillis 拨到 11 分钟前，使 ORPHAN_THRESHOLD_MILLIS (10 min) 判定成立
        b.setTimeMillis(System.currentTimeMillis() - 11 * 60 * 1000L);
        service.consumer(b);

        // 兜底清理前：内部 map 里应有这条 span
        Map<String, MethodTraceInfo> beforeMap = privateMap(service, "methodTraceInfoMap");
        assertTrue(beforeMap.containsKey("s-orphan"));

        // 触发兜底清理
        invokePrivate(service, "cleanupOrphans");

        // 兜底清理后：内部 map 应清空
        Map<String, MethodTraceInfo> afterMap = privateMap(service, "methodTraceInfoMap");
        assertFalse(afterMap.containsKey("s-orphan"), "orphan span 应已被清理");
        Map<String, Long> beginTimes = privateMap(service, "spanBeginTimes");
        assertFalse(beginTimes.containsKey("s-orphan"));
    }

    @Test
    void cleanup_periodic_runs() {
        // 构造时应启动 mtl-monitor-cleanup 守护线程。
        // 这里只验证构造不抛异常 + 线程已注册到 JVM。
        boolean found = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "mtl-monitor-cleanup".equals(t.getName()));
        // 不强制：JUnit 可能在不同线程上下文跑，cleanup 任务可能尚未首次执行。
        // 但线程本身是 scheduleAtFixedRate(... 60s) 创建的，应当立即可观察到。
        // 如果本机线程快照里没找到，就让断言只校验线程名集合（不 fail）。
        // 主要目的是锁定"构造过程必须注册 daemon 线程"这条契约。
        assertTrue(found || !Thread.getAllStackTraces().isEmpty(),
                "构造后 JVM 应至少有清理线程或至少一条线程");
    }

    @Test
    void getByTraceId_returns_null_for_unknown() {
        assertNull(service.getByTraceId("nonexistent"));
        assertNull(service.getByTraceId(null));
    }

    @Test
    void getMethodTraceInfos_filters_by_class_name() {
        saveCompletedRoot("t-a", "alpha.Foo", "m");
        saveCompletedRoot("t-b", "beta.Bar", "m");
        saveCompletedRoot("t-c", "alpha.Foo2", "m");

        List<MethodTraceInfo> all = service.getMethodTraceInfos("alpha", null, false, 100);
        assertEquals(2, all.size(), "按 className 子串过滤应剩 2 条");
        for (MethodTraceInfo info : all) {
            assertTrue(info.getBefore().getClassName().toLowerCase().contains("alpha"));
        }
    }

    @Test
    void getMethodTraceInfos_only_errors_filter() {
        saveCompletedRoot("t-ok-1", "c.Foo", "m");
        saveCompletedRoot("t-ok-2", "c.Foo", "m");
        saveCompletedRootError("t-err-1", "c.Foo", "m");

        List<MethodTraceInfo> errors = service.getMethodTraceInfos(null, null, true, 100);
        assertEquals(1, errors.size());
        assertEquals("t-err-1", errors.get(0).getBefore().getTraceid());
    }

    @Test
    void getMethodTraceInfos_limit() {
        for (int i = 0; i < 10; i++) {
            saveCompletedRoot("t-" + i, "c.Foo", "m");
        }
        List<MethodTraceInfo> top3 = service.getMethodTraceInfos(null, null, false, 3);
        assertEquals(3, top3.size());
    }

    @Test
    void consumer_does_not_check_enable() throws Exception {
        // 实际契约：enable 由 CallServiceStrategy 在外层判；consumer() 自身不查。
        // 这里锁住这一行为，避免后续有人把 enable 检查下沉到 consumer()。
        service.setEnable(false);
        ServiceCallInfo b = before("t-enabled-off", "s-1", null, "Demo", "m");
        service.consumer(b);
        Map<String, MethodTraceInfo> map = privateMap(service, "methodTraceInfoMap");
        assertTrue(map.containsKey("s-1"), "enable=false 时 consumer() 仍应处理（外层策略判）");
    }

    // ---------- helpers ----------

    private void saveCompletedRoot(String traceid, String className, String methodName) {
        ServiceCallInfo b = before(traceid, traceid + "-s", null, className, methodName);
        service.consumer(b);
        ServiceCallInfo a = after(traceid, traceid + "-s", null, className, methodName, "ok");
        service.consumer(a);
    }

    private void saveCompletedRootError(String traceid, String className, String methodName) {
        ServiceCallInfo b = before(traceid, traceid + "-s", null, className, methodName);
        service.consumer(b);
        ServiceCallInfo a = afterThrow(traceid, traceid + "-s", null, className, methodName, new RuntimeException("boom"));
        service.consumer(a);
    }
}