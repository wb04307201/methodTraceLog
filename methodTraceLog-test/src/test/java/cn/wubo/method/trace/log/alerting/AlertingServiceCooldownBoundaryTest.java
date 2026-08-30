package cn.wubo.method.trace.log.alerting;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AlertingService cooldown 边界测试。
 * <p>
 * 修复前：{@code now - last <= cooldownMs} 抑制错误，把边界值（exactly == cooldownMs）
 * 也吞了。修复后：{@code now - last < cooldownMs} 才抑制，等于 cooldownMs 时正确放行，
 * 与文档承诺的"cooldown 期外必须能重新触发"一致。
 * <p>
 * 用 {@link MutableClock} 让测试可以"跳到"任意时间点，绕开 Thread.sleep 的不稳定。
 */
class AlertingServiceCooldownBoundaryTest {

    /** 可手动步进的 Clock（毫秒精度）。 */
    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(0L);
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
        void advance(long ms) { now.addAndGet(ms); }
        void set(long ms) { now.set(ms); }
    }

    private ServiceCallInfo throwInfo(String cls, String m, String tid) {
        ServiceCallInfo i = new ServiceCallInfo();
        i.setLogActionEnum(LogActionEnum.AFTER_THROW);
        i.setClassName(cls);
        i.setMethodName(m);
        i.setTraceid(tid);
        i.setContext("boom");
        i.setTimeMillis(System.currentTimeMillis());
        return i;
    }

    private AlertingService newService(MethodTraceLogProperties.AlertingProperties props, MutableClock clock) {
        // webhookUrl 留空避免实际 HTTP 调用
        props.setWebhookUrl("");
        return new AlertingService(props, RestClient.create("http://localhost:9"), clock);
    }

    @Test
    void at_boundary_equalsCooldownMs_reFires() {
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(60);
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(120);

        MutableClock clock = new MutableClock();
        AlertingService svc = newService(props, clock);

        // T0: 触发一次（throw × 3 = 跨阈值）
        clock.set(1_000_000L);
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t0-" + i));
        }
        Assertions.assertEquals(1, svc.getRecent(10).size(), "T0 跨阈值应产生 1 条事件");

        // 推进到 T0 + cooldownMs（恰好等于）
        clock.set(1_000_000L + 60_000L);
        // 再次 throw × 3 → 窗口内 ≥ 阈值 → cooldown 边界外 → 必须重新触发
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t1-" + i));
        }

        List<AlertEvent> events = svc.getRecent(10);
        Assertions.assertEquals(2, events.size(),
                "now - last == cooldownMs（边界）应放行重新触发；当前事件数=" + events.size());
    }

    @Test
    void just_under_boundary_stillSuppressed() {
        // 反向：now - last = cooldownMs - 1ms 时必须抑制（验证边界另一侧）
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(60);
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(120);

        MutableClock clock = new MutableClock();
        AlertingService svc = newService(props, clock);

        clock.set(1_000_000L);
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t0-" + i));
        }
        Assertions.assertEquals(1, svc.getRecent(10).size());

        // T0 + 60_000 - 1 = 边界内
        clock.set(1_000_000L + 60_000L - 1L);
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t1-" + i));
        }

        Assertions.assertEquals(1, svc.getRecent(10).size(),
                "now - last < cooldownMs 必须抑制；当前事件数=" + svc.getRecent(10).size());
    }

    @Test
    void zeroCooldown_alwaysRefires() {
        // cooldownSeconds=0 时（业务配置），每次跨阈值都应立即触发
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(0);
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(120);

        MutableClock clock = new MutableClock();
        AlertingService svc = newService(props, clock);

        // 第一次跨阈值（throw × 3 全部命中，第一次过阈值时触发）
        clock.set(1_000_000L);
        for (int i = 0; i < 3; i++) svc.consumer(throwInfo("X", "m", "a-" + i));
        Assertions.assertEquals(1, svc.getRecent(10).size());

        // 1ms 后再 throw × 3：
        //   - throw #1：窗口累加到 4，cooldown=0 → 不抑制 → 触发事件 #2
        //   - throw #2：累加到 5 → 触发事件 #3
        //   - throw #3：累加到 6 → 触发事件 #4
        // 总共 4 条事件 —— 表明 cooldown=0 时每次阈值跨过都触发
        clock.advance(1L);
        for (int i = 0; i < 3; i++) svc.consumer(throwInfo("X", "m", "b-" + i));
        Assertions.assertEquals(4, svc.getRecent(10).size(),
                "cooldown=0 时每次跨阈值都触发（不再抑制）");
    }
}