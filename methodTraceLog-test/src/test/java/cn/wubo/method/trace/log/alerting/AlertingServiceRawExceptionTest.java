package cn.wubo.method.trace.log.alerting;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

/**
 * AlertingService raw exception 完整堆栈回归测试（F-06）。
 * <p>
 * 修复前：{@code AlertingService.consumer()} 通过 {@code transContext(info.getContext())}
 * 把异常转成字符串 —— 走的是 {@link cn.wubo.method.trace.log.AbstractCallService#transContext}
 * 的 10 行截断 + 换行拼接路径，丢失大部分 stacktrace 上下文，运维定位慢。
 * <p>
 * 修复后：直接用 {@link ServiceCallInfo#getRawException()} 拿原始 Throwable，toString
 * 出类名 + message + 完整 stacktrace。
 * <p>
 * 本测试用 {@code setRawException(...)} 模拟 {@link cn.wubo.method.trace.log.LogAspect}
 * 在 catch 分支写入的 rawException 字段（见 LogAspect.java:220），断言告警 body 包含
 * "at cn.wubo..." 形式的完整堆栈行。
 */
class AlertingServiceRawExceptionTest {

    private ServiceCallInfo throwInfo(String cls, String m, String tid, Throwable rawEx) {
        ServiceCallInfo i = new ServiceCallInfo();
        i.setLogActionEnum(LogActionEnum.AFTER_THROW);
        i.setClassName(cls);
        i.setMethodName(m);
        i.setTraceid(tid);
        i.setContext("should be ignored when rawException is set");
        i.setRawException(rawEx);
        i.setTimeMillis(System.currentTimeMillis());
        return i;
    }

    @Test
    void alertBody_containsFullStackTraceFromRawException() {
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(0);
        props.getThreshold().setErrorCount(1);
        props.getThreshold().setWindowSeconds(60);
        props.setWebhookUrl("");

        AlertingService svc = new AlertingService(props, RestClient.create("http://localhost:9"), Clock.systemUTC());

        // 构造一个有清晰调用栈的自定义异常 —— 测试类本身的方法一定在栈里
        IllegalStateException ex;
        try {
            throw new IllegalStateException("custom-raw-exception-marker");
        } catch (IllegalStateException e) {
            ex = e;
        }

        // 单次即可触发（errorCount=1）
        svc.consumer(throwInfo("X", "m", "t-1", ex));

        List<AlertEvent> events = svc.getRecent(10);
        Assertions.assertEquals(1, events.size(), "应产生 1 条告警");
        AlertEvent event = events.get(0);

        String body = event.getSampleError();
        Assertions.assertNotNull(body, "sampleError 必须非空");
        // 包含原始异常类名
        Assertions.assertTrue(body.contains("IllegalStateException"),
                "告警 body 应包含异常类名；实际: " + body);
        // 包含原始 message
        Assertions.assertTrue(body.contains("custom-raw-exception-marker"),
                "告警 body 应包含原始 message；实际: " + body);
        // 关键：包含完整 stacktrace 行（"at " 前缀）
        Assertions.assertTrue(body.contains("\tat "),
                "告警 body 应包含完整 stacktrace（'\\tat' 形式）；实际: " + body);
        // 验证 stacktrace 包含本测试类的位置
        Assertions.assertTrue(body.contains("cn.wubo"),
                "告警 body 应包含本测试类的 stacktrace；实际: " + body);
    }

    @Test
    void alertBody_fallsBackToContext_whenRawExceptionMissing() {
        // rawException==null 时（极少数场景 —— 可能是测试或老旧调用方直接构造 ServiceCallInfo 喂进来），
        // 仍需正常工作：fallback 到 context。
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(0);
        props.getThreshold().setErrorCount(1);
        props.getThreshold().setWindowSeconds(60);
        props.setWebhookUrl("");

        AlertingService svc = new AlertingService(props, RestClient.create("http://localhost:9"), Clock.systemUTC());

        ServiceCallInfo i = new ServiceCallInfo();
        i.setLogActionEnum(LogActionEnum.AFTER_THROW);
        i.setClassName("X");
        i.setMethodName("m");
        i.setTraceid("t-1");
        i.setContext("legacy-string-error");
        // 故意不设 rawException
        i.setTimeMillis(System.currentTimeMillis());

        svc.consumer(i);

        List<AlertEvent> events = svc.getRecent(10);
        Assertions.assertEquals(1, events.size());
        // fallback 路径：body 包含 context 的字符串内容（经 transContext 处理）
        Assertions.assertTrue(events.get(0).getSampleError().contains("legacy-string-error"),
                "rawException==null 时应 fallback 到 context；实际: "
                        + events.get(0).getSampleError());
    }
}
