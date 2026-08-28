package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CallServiceStrategy} 的 fan-out / enable 切换测试。
 * <p>
 * 用最简的 {@link AbstractCallService} 子类充当 spy，记录
 * "被调用了几次 / 当前 enable 状态"。这种手写 spy 比 Mockito 更直观，
 * 也避免了不必要的 mock 反射开销。
 */
class CallServiceStrategyTest {

    /** 最小的 spy 实现：只记录调用次数与当前 enable。 */
    static class SpyService extends AbstractCallService {
        final AtomicInteger callCount = new AtomicInteger();
        private final String name;
        private final String desc;

        SpyService(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override
        public void consumer(ServiceCallInfo serviceCallInfo) {
            callCount.incrementAndGet();
        }

        @Override
        public String getCallServiceName() {
            return name;
        }

        @Override
        public String getCallServiceDesc() {
            return desc;
        }
    }

    private static ServiceCallInfo sampleEvent() {
        return new ServiceCallInfo(
                "trace-1",
                null,
                "span-1",
                "Demo",
                "Demo",
                "m",
                "m()",
                "m()",
                List.of(),
                LogActionEnum.BEFORE,
                System.currentTimeMillis());
    }

    @Test
    void iterates_all_services() {
        SpyService a = new SpyService("A", "desc-A");
        SpyService b = new SpyService("B", "desc-B");
        CallServiceStrategy strategy = new CallServiceStrategy(
                Arrays.asList(a, b),
                new MethodTraceLogProperties());

        strategy.consumer(sampleEvent());

        assertEquals(1, a.callCount.get(), "A 应当被调用");
        assertEquals(1, b.callCount.get(), "B 应当被调用");
    }

    @Test
    void skips_disabled_service() {
        SpyService enabled = new SpyService("E", "enabled");
        SpyService disabled = new SpyService("D", "disabled");
        disabled.setEnable(false);
        CallServiceStrategy strategy = new CallServiceStrategy(
                Arrays.asList(enabled, disabled),
                new MethodTraceLogProperties());

        strategy.consumer(sampleEvent());

        assertEquals(1, enabled.callCount.get());
        assertEquals(0, disabled.callCount.get(), "disabled 的服务应被跳过");
    }

    @Test
    void null_in_list_throws_npe_locking_current_contract() {
        // 实际行为：CallServiceStrategy.consumer() 不做 null 检查，遇到 null 会 NPE。
        // 这里锁住这一行为，避免以后悄悄改语义。
        // 若未来想 "tolerate null"，应改 production code 并同步改这里。
        SpyService a = new SpyService("A", "desc-A");
        List<ICallService> list = new ArrayList<>();
        list.add(a);
        list.add(null);

        CallServiceStrategy strategy = new CallServiceStrategy(list, new MethodTraceLogProperties());

        assertThrows(NullPointerException.class,
                () -> strategy.consumer(sampleEvent()));
        // a 在 null 之前已经被遍历过，所以仍记到 1 次
        assertEquals(1, a.callCount.get());
    }

    @Test
    void setCallServiceEnable_toggles_at_runtime() {
        SpyService svc = new SpyService("Toggle", "toggle-me");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(svc),
                new MethodTraceLogProperties());

        // 关闭
        List<Map<String, Object>> afterDisable = strategy.setCallServiceEnable("Toggle", false);
        strategy.consumer(sampleEvent());
        assertEquals(0, svc.callCount.get(), "关闭后不应被调用");
        assertEquals(false, afterDisable.stream()
                .filter(m -> "Toggle".equals(m.get("name")))
                .findFirst()
                .map(m -> m.get("enable"))
                .orElse(null));

        // 打开
        List<Map<String, Object>> afterEnable = strategy.setCallServiceEnable("Toggle", true);
        strategy.consumer(sampleEvent());
        assertEquals(1, svc.callCount.get(), "打开后应被调用");
        assertEquals(true, afterEnable.stream()
                .filter(m -> "Toggle".equals(m.get("name")))
                .findFirst()
                .map(m -> m.get("enable"))
                .orElse(null));
    }

    @Test
    void getCallServices_lists_all() {
        SpyService a = new SpyService("A", "desc-A");
        SpyService b = new SpyService("B", "desc-B");
        CallServiceStrategy strategy = new CallServiceStrategy(
                Arrays.asList(a, b),
                new MethodTraceLogProperties());

        List<Map<String, Object>> list = strategy.getCallServices();
        assertEquals(2, list.size());
        // 每条都应带 name/desc/enable 三个键
        for (Map<String, Object> entry : list) {
            assertNotNull(entry.get("name"));
            assertNotNull(entry.get("desc"));
            assertNotNull(entry.get("enable"));
        }
    }

    @Test
    void empty_service_list_is_safe() {
        CallServiceStrategy strategy = new CallServiceStrategy(
                Collections.emptyList(),
                new MethodTraceLogProperties());
        assertDoesNotThrow(() -> strategy.consumer(sampleEvent()));
        assertTrue(strategy.getCallServices().isEmpty());
    }
}