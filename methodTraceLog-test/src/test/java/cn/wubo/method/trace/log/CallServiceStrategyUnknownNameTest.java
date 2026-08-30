package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-76 / R-85: {@link CallServiceStrategy#setCallServiceEnable(String, Boolean)} 对未知
 * 服务名 / null 服务名 / null enable 的行为必须是静默 no-op，不抛。
 * <p>
 * 现状（生产代码）：
 * <pre>{@code
 *   for (ICallService callService : callServices) {
 *     if (callService.getCallServiceName().equals(name)) {
 *       callService.setEnable(enable);
 *       break;
 *     }
 *   }
 * }</pre>
 * 没找到匹配的 name → for 循环空跑一遍 → 继续返回 getCallServices()。
 * 不抛，也不记录日志（注意是 silent no-op）。
 * <p>
 * 同样：{@code enable=null} 会调 {@code ICallService.setEnable(null)}，可能让
 * {@link AbstractCallService#getEnable()} 返回 null（{@code Boolean.TRUE.equals(null) == false} →
 * {@code CallServiceStrategy.consumer} 跳过该服务）。这里锁住"不抛"的契约。
 */
class CallServiceStrategyUnknownNameTest {

    /** Spy 服务用于断言 setCallServiceEnable 的副作用。 */
    static class SpyService extends AbstractCallService {
        final AtomicInteger callCount = new AtomicInteger();
        private final String name;
        private Boolean lastSetEnable;

        SpyService(String name) {
            this.name = name;
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
            return "spy-" + name;
        }

        @Override
        public void setEnable(Boolean enable) {
            this.lastSetEnable = enable;
            super.setEnable(enable);
        }

        Boolean getLastSetEnable() {
            return lastSetEnable;
        }
    }

    @Test
    void unknownServiceName_isSilentNoOp() {
        SpyService a = new SpyService("A");
        SpyService b = new SpyService("B");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a, b), new MethodTraceLogProperties());

        // 期望：传入不存在的 name "Ghost" 不抛
        List<Map<String, Object>> result = assertDoesNotThrow(
                () -> strategy.setCallServiceEnable("Ghost", false),
                "未知服务名应是静默 no-op，不应抛 IllegalArgumentException");

        // 返回值仍是全部服务列表
        assertEquals(2, result.size());
        // 现有服务的 enable 不应被改
        for (SpyService spy : List.of(a, b)) {
            assertEquals(null, spy.getLastSetEnable(),
                    "未知 name 不应触发现有服务的 setEnable()");
            assertTrue(spy.getEnable(), "现有服务的 enable 仍为 true（默认）");
        }
    }

    @Test
    void unknownServiceName_doesNotAffectConsumerDispatch() {
        SpyService a = new SpyService("A");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a), new MethodTraceLogProperties());

        strategy.setCallServiceEnable("Ghost", false);

        ServiceCallInfo ev = sampleEvent();
        strategy.consumer(ev);
        // a 仍应被调用（enable 默认 true，未被未知 name 的调用误关）
        assertEquals(1, a.callCount.get());
    }

    @Test
    void nullServiceName_isSilentNoOp() {
        SpyService a = new SpyService("A");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a), new MethodTraceLogProperties());

        List<Map<String, Object>> result = assertDoesNotThrow(
                () -> strategy.setCallServiceEnable(null, false));

        assertEquals(1, result.size());
        assertTrue(a.getEnable());
    }

    @Test
    void nullEnable_isAllowedAndStored() {
        // ICallService.setEnable(Boolean) 签名接受 null —— 锁住此契约
        // （CallServiceStrategy.consumer 用 Boolean.TRUE.equals(...) 判断，所以
        //  enable==null 时该服务会被静默跳过，这是 fan-out 的预期行为）。
        SpyService a = new SpyService("A");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a), new MethodTraceLogProperties());

        assertDoesNotThrow(() -> strategy.setCallServiceEnable("A", null));
        assertEquals(null, a.getLastSetEnable(),
                "enable=null 必须被存进 ICallService.enable（lock 契约）");
        // 后续 dispatch 应跳过（因为 Boolean.TRUE.equals(null) == false）
        strategy.consumer(sampleEvent());
        assertEquals(0, a.callCount.get(),
                "enable=null 时 CallServiceStrategy.consumer 必须跳过该服务");
    }

    @Test
    void emptyServiceList_setEnable_isNoOp() {
        // 防御：空 list 上调 setEnable 不应抛 / 不应 NPE
        CallServiceStrategy strategy = new CallServiceStrategy(
                Collections.emptyList(), new MethodTraceLogProperties());

        assertDoesNotThrow(() -> strategy.setCallServiceEnable("Anything", false));
    }

    @Test
    void multipleServices_partialMatch_onlyAffectsTarget() {
        SpyService a = new SpyService("A");
        SpyService b = new SpyService("B");
        SpyService c = new SpyService("C");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a, b, c), new MethodTraceLogProperties());

        strategy.setCallServiceEnable("B", false);

        assertEquals(null, a.getLastSetEnable(), "A 不应被影响");
        assertEquals(false, b.getLastSetEnable(), "B 应被设为 false");
        assertEquals(null, c.getLastSetEnable(), "C 不应被影响");
        // b 的 enable 确实变了
        assertFalse(b.getEnable());
        // 全部服务的 enable 状态：1 关 2 开
        long enabledCount = strategy.getCallServices().stream()
                .filter(m -> Boolean.TRUE.equals(m.get("enable")))
                .count();
        assertEquals(2L, enabledCount, "仅 B 被关，A 和 C 应保持开");
    }

    @Test
    void duplicateServiceName_firstMatchWins() {
        // 两个服务同名 —— CallServiceStrategy.consumer 会调两次，
        // setCallServiceEnable 会作用于第一个匹配，第二个不受影响。
        // 这是当前实现行为，锁住契约。
        SpyService a1 = new SpyService("Dup");
        SpyService a2 = new SpyService("Dup");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a1, a2), new MethodTraceLogProperties());

        strategy.setCallServiceEnable("Dup", false);
        assertEquals(false, a1.getLastSetEnable(), "同名第一个应被改");
        assertEquals(null, a2.getLastSetEnable(), "同名第二个不应被改（first-match-wins）");
    }

    @Test
    void getCallServices_returnsAllAfterUnknownName() {
        SpyService a = new SpyService("A");
        SpyService b = new SpyService("B");
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(a, b), new MethodTraceLogProperties());

        List<Map<String, Object>> result = strategy.setCallServiceEnable("Ghost", true);
        assertEquals(2, result.size(), "即使未知 name，返回值仍应包含全部已注册服务");
        // 返回值字段
        for (Map<String, Object> entry : result) {
            assertTrue(entry.containsKey("name"));
            assertTrue(entry.containsKey("desc"));
            assertTrue(entry.containsKey("enable"));
        }
    }

    private static ServiceCallInfo sampleEvent() {
        return new ServiceCallInfo(
                "trace-1", null, "span-1",
                "Demo", "Demo", "m", "m()", "m()",
                List.of(), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void consumerList_nullListName_throwsNPE() {
        // 边界：当某个 ICallService.getCallServiceName() 返回 null 时，
        // CallServiceStrategy 内部用 `callService.getCallServiceName().equals(name)`，
        // null.equals(...) 会 NPE。这是当前实现行为，锁住契约 —— 防止以后
        // 悄悄改用 Objects.equals(...) 让行为变了。
        SpyService nullName = new SpyService(null) {
            @Override
            public String getCallServiceName() {
                return null;
            }
        };
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(nullName), new MethodTraceLogProperties());

        // 即便 name 是非 null "any-name"，nullName.getCallServiceName() 是 null，
        // → null.equals("any-name") 抛 NPE。
        assertThrows(NullPointerException.class,
                () -> strategy.setCallServiceEnable("any-name", false),
                "ICallService.getCallServiceName() 返回 null 时 setCallServiceEnable 会 NPE（null.equals(...)）");

        // 同样：name 是 null 时 receiver 仍是 null → NPE
        assertThrows(NullPointerException.class,
                () -> strategy.setCallServiceEnable(null, false),
                "null name + null getCallServiceName 都会 NPE");
    }
}