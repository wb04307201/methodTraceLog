package cn.wubo.method.trace.log.alerting;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

/**
 * {@link AlertingService} 行为测试：滑动窗口阈值、cooldown 抑制、类白名单、
 * action 过滤、总开关、空 webhook 兜底。
 * <p>
 * webhook 指向 {@code http://localhost:9}（discard 端口）：tests 里 {@code webhookUrl}
 * 始终为空串，所以实际不会发起任何 HTTP 请求。
 */
class AlertingServiceTest {

    private MethodTraceLogProperties.AlertingProperties props;
    private AlertingService svc;

    @BeforeEach
    void setUp() {
        props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(60);
        props.setCooldownSeconds(0); // 测试期不冷却
        svc = new AlertingService(props, RestClient.create("http://localhost:9"), Clock.systemUTC());
    }

    private ServiceCallInfo throwInfo(String cls, String m, String tid, String err) {
        ServiceCallInfo i = new ServiceCallInfo();
        i.setLogActionEnum(LogActionEnum.AFTER_THROW);
        i.setClassName(cls);
        i.setMethodName(m);
        i.setTraceid(tid);
        i.setContext(err);
        i.setTimeMillis(System.currentTimeMillis());
        return i;
    }

    @Test
    @DisplayName("窗口内错误数未到阈值时不产生告警")
    void under_threshold_does_not_alert() {
        for (int i = 0; i < 2; i++) {
            svc.consumer(throwInfo("X", "m", "t-" + i, "boom"));
        }
        Assertions.assertTrue(svc.getRecent(10).isEmpty());
    }

    @Test
    @DisplayName("跨过阈值时产生一条 error_threshold 事件并进入 ring buffer")
    void crossing_threshold_records_event_and_pushes_to_ring_buffer() {
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t-" + i, "boom"));
        }
        List<AlertEvent> events = svc.getRecent(10);
        Assertions.assertEquals(1, events.size());
        AlertEvent e = events.get(0);
        Assertions.assertEquals("X", e.getClassName());
        Assertions.assertEquals("m", e.getMethodName());
        Assertions.assertEquals("error_threshold", e.getType());
        Assertions.assertEquals(3, e.getErrorCount());
        Assertions.assertEquals(60, e.getWindowSeconds());
    }

    @Test
    @DisplayName("不同方法各自独立计窗口")
    void different_methods_tracked_independently() {
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m1", "t-" + i, "boom"));
        }
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m2", "u-" + i, "boom"));
        }
        Assertions.assertEquals(2, svc.getRecent(10).size());
    }

    @Test
    @DisplayName("cooldown 内同一方法的重复越界被抑制")
    void cooldown_suppresses_repeat_alerts_for_same_method() {
        props.setCooldownSeconds(60);
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t-" + i, "boom"));
        }
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("X", "m", "t2-" + i, "boom"));
        }
        Assertions.assertEquals(1, svc.getRecent(10).size());
    }

    @Test
    @DisplayName("classes 白名单过滤掉未列出的类")
    void classes_whitelist_filters_unlisted_classes() {
        props.setClasses(List.of("cn.wubo.allowed"));
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("cn.wubo.allowed.X", "m", "t-" + i, "boom"));
        }
        for (int i = 0; i < 3; i++) {
            svc.consumer(throwInfo("cn.wubo.other.Y", "m", "u-" + i, "boom"));
        }
        List<AlertEvent> events = svc.getRecent(10);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals("cn.wubo.allowed.X", events.get(0).getClassName());
    }

    @Test
    @DisplayName("BEFORE / AFTER_RETURN 事件被忽略")
    void before_and_after_return_are_ignored() {
        ServiceCallInfo before = new ServiceCallInfo();
        before.setLogActionEnum(LogActionEnum.BEFORE);
        before.setClassName("X");
        before.setMethodName("m");
        before.setTraceid("t");
        before.setTimeMillis(System.currentTimeMillis());
        svc.consumer(before);

        ServiceCallInfo afterReturn = new ServiceCallInfo();
        afterReturn.setLogActionEnum(LogActionEnum.AFTER_RETURN);
        afterReturn.setClassName("X");
        afterReturn.setMethodName("m");
        afterReturn.setTraceid("t");
        afterReturn.setTimeMillis(System.currentTimeMillis());
        svc.consumer(afterReturn);

        Assertions.assertTrue(svc.getRecent(10).isEmpty());
    }

    @Test
    @DisplayName("enable=false 时完全不工作")
    void disabled_does_nothing() {
        props.setEnable(false);
        for (int i = 0; i < 100; i++) {
            svc.consumer(throwInfo("X", "m", "t-" + i, "boom"));
        }
        Assertions.assertTrue(svc.getRecent(10).isEmpty());
    }

    @Test
    @DisplayName("webhookUrl 为空时不抛异常，仍然记录到 ring buffer")
    void empty_webhook_url_does_not_throw() {
        props.setWebhookUrl("");
        // 即使没有 webhook 也不应抛
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Assertions.assertDoesNotThrow(() -> svc.consumer(throwInfo("X", "m", "t-" + idx, "boom")));
        }
        Assertions.assertEquals(1, svc.getRecent(10).size());
    }
}
