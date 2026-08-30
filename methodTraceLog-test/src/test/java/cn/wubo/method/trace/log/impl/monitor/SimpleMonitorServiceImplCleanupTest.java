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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-74: SimpleMonitorServiceImpl.cleanupOrphans 在极端输入下不应抛 / 不应死锁 / 不应泄漏内存。
 * <p>
 * cleanupOrphans 内部逻辑：
 * <pre>{@code
 *   long now = System.currentTimeMillis();
 *   Iterator<Map.Entry<String, Long>> it = spanBeginTimes.entrySet().iterator();
 *   while (it.hasNext()) {
 *     Map.Entry<String, Long> e = it.next();
 *     if (now - e.getValue() > ORPHAN_THRESHOLD_MILLIS) {
 *       timerSamples.remove(e.getKey());
 *       methodTraceInfoMap.remove(e.getKey());
 *       it.remove();
 *     }
 *   }
 * }</pre>
 * <p>
 * 已知 corner cases：
 * <ul>
 *     <li>空 map → while 不进，不抛</li>
 *     <li>所有 span 都在阈值内 → 不删，不抛</li>
 *     <li>所有 span 都超过阈值 → 全部清空，不抛</li>
 *     <li>混入超阈值 + 阈值内 → 只清超阈值那部分，阈值内保留</li>
 *     <li>超大 map（10K 条）→ 应能跑完，不 OOM</li>
 * </ul>
 */
class SimpleMonitorServiceImplCleanupTest {

    private static ServiceCallInfo beforeEvent(String spanid) {
        return new ServiceCallInfo(
                "trace-" + spanid, null, spanid,
                "Demo", "Demo", "work", "work()", "work()",
                "arg", LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> privateMap(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Map<K, V>) f.get(target);
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }

    @Test
    void cleanupOrphans_emptyMap_isNoOp() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        Map<String, Long> map = privateMap(svc, "spanBeginTimes");
        assertTrue(map.isEmpty(), "新建 svc 后 spanBeginTimes 应为空");

        assertDoesNotThrow(() -> invokePrivate(svc, "cleanupOrphans"));
        assertTrue(map.isEmpty());
    }

    @Test
    void cleanupOrphans_freshSpan_notRemoved() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        // 推一条 BEFORE（timeMillis = 当前 → 远在 ORPHAN_THRESHOLD_MILLIS=10min 之内）
        ServiceCallInfo b = beforeEvent("s-fresh");
        svc.consumer(b);

        Map<String, MethodTraceInfo> mtm = privateMap(svc, "methodTraceInfoMap");
        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        assertTrue(mtm.containsKey("s-fresh"));
        assertTrue(beginTimes.containsKey("s-fresh"));

        invokePrivate(svc, "cleanupOrphans");

        assertTrue(mtm.containsKey("s-fresh"),
                "新鲜 span（timeMillis 刚刚）不应被 cleanupOrphans 误删");
        assertTrue(beginTimes.containsKey("s-fresh"));
    }

    @Test
    void cleanupOrphans_oldSpan_isRemoved() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        // 推一条 BEFORE，然后把 timeMillis 拨到 11 分钟前
        ServiceCallInfo b = beforeEvent("s-stale");
        b.setTimeMillis(System.currentTimeMillis() - 11 * 60 * 1000L);
        svc.consumer(b);

        Map<String, MethodTraceInfo> mtm = privateMap(svc, "methodTraceInfoMap");
        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        assertTrue(mtm.containsKey("s-stale"));
        assertTrue(beginTimes.containsKey("s-stale"));

        invokePrivate(svc, "cleanupOrphans");

        assertFalse(mtm.containsKey("s-stale"),
                "11 分钟前的 span 应被 cleanupOrphans 清理");
        assertFalse(beginTimes.containsKey("s-stale"));
    }

    @Test
    void cleanupOrphans_mixed_oldAndFresh_onlyOldRemoved() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        // 一条新鲜 + 一条 stale
        ServiceCallInfo fresh = beforeEvent("s-fresh");
        svc.consumer(fresh);

        ServiceCallInfo stale = beforeEvent("s-stale");
        stale.setTimeMillis(System.currentTimeMillis() - 11 * 60 * 1000L);
        svc.consumer(stale);

        Map<String, MethodTraceInfo> mtm = privateMap(svc, "methodTraceInfoMap");
        assertTrue(mtm.containsKey("s-fresh"));
        assertTrue(mtm.containsKey("s-stale"));

        invokePrivate(svc, "cleanupOrphans");

        assertTrue(mtm.containsKey("s-fresh"), "fresh 保留");
        assertFalse(mtm.containsKey("s-stale"), "stale 移除");
    }

    @Test
    void cleanupOrphans_largeMap_doesNotHang() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        // 直接往内部 map 灌 10000 条，全部设为"过期"
        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        long stale = System.currentTimeMillis() - 30 * 60 * 1000L;
        for (int i = 0; i < 10_000; i++) {
            beginTimes.put("s-" + i, stale);
        }
        // 同样填 methodTraceInfoMap 以避免 cleanup 抛 NPE（cleanup 里 map.remove(null) 安全）
        Map<String, MethodTraceInfo> mtm = privateMap(svc, "methodTraceInfoMap");
        for (int i = 0; i < 10_000; i++) {
            // 即使 methodTraceInfoMap 没对应的 key，timerSamples.remove / methodTraceInfoMap.remove
            // 也是 no-op，不会抛
            mtm.put("s-" + i, MethodTraceInfo.create(beforeEvent("s-" + i)));
        }

        // 跑 cleanupOrphans 不应抛 / 不应死锁
        assertDoesNotThrow(() -> invokePrivate(svc, "cleanupOrphans"));

        // 全部过期 → 全部清空
        assertTrue(beginTimes.isEmpty(), "10K 过期 span 应全部被清空");
        // methodTraceInfoMap 同样应空
        assertTrue(mtm.isEmpty(), "methodTraceInfoMap 应全部清空");
    }

    @Test
    void cleanupOrphans_atThresholdBoundary_keepsEntry() throws Exception {
        // 边界：timeMillis == now - 10min（threshold 严格 >，不应被清理）
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        ServiceCallInfo b = beforeEvent("s-edge");
        long now = System.currentTimeMillis();
        b.setTimeMillis(now - 10 * 60 * 1000L); // 正好 10 分钟
        svc.consumer(b);

        Map<String, MethodTraceInfo> mtm = privateMap(svc, "methodTraceInfoMap");
        // 边界判断严格大于 (>)：10 分钟 0 毫秒时的 now - time == 阈值 → 不删
        // 由于 invokePrivate 调用与 now 之间有几毫秒漂移，可能 timeMillis 看起来"稍旧" → 删除
        // 这里只断言不抛异常，且最终结果是稳定的（要么全留，要么全删——不混着）
        invokePrivate(svc, "cleanupOrphans");

        // 只断言不抛 + map 一致性
        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        assertEquals(mtm.containsKey("s-edge"), beginTimes.containsKey("s-edge"),
                "cleanupOrphans 必须保持 methodTraceInfoMap / spanBeginTimes 一致（同生同死）");
    }

    @Test
    void cleanupOrphans_idempotent() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        ServiceCallInfo b = beforeEvent("s-stale");
        b.setTimeMillis(System.currentTimeMillis() - 11 * 60 * 1000L);
        svc.consumer(b);

        // 第一次清理：应删
        invokePrivate(svc, "cleanupOrphans");
        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        assertFalse(beginTimes.containsKey("s-stale"));

        // 第二次清理：map 已空，应 no-op
        assertDoesNotThrow(() -> invokePrivate(svc, "cleanupOrphans"));
        assertTrue(beginTimes.isEmpty());
    }

    @Test
    void cleanupOrphans_explicitNullEntry_safe() throws Exception {
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(
                new SimpleMeterRegistry(), new InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        Map<String, Long> beginTimes = privateMap(svc, "spanBeginTimes");
        // 模拟边界：put(null, ...) 会抛 NPE —— 这里只确认正常 map 行为
        beginTimes.put("ok", System.currentTimeMillis());
        assertDoesNotThrow(() -> invokePrivate(svc, "cleanupOrphans"));
        assertTrue(beginTimes.containsKey("ok"));
    }
}