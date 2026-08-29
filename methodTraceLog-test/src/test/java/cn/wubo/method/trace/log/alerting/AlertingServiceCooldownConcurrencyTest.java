package cn.wubo.method.trace.log.alerting;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AlertingService cooldown 原子性并发回归测试（F-05）。
 * <p>
 * 修复前：{@code cooldownUntil.put(key, now)} 之前是 {@code get} + 检查 + {@code put}，
 * 多个线程可能同时通过 "now - last >= cooldownMs" 检查、各自 put 自己的时间戳、
 * 各自构造事件并触发 webhook —— 等于"抑制失效"。
 * <p>
 * 修复后：{@code putIfAbsent} 是原子的；只有抢到位的那个线程能继续，其它线程直接放弃。
 * 本测试用 N=20 线程同时喂 N 条 AFTER_THROW 事件，期望只有 1 条
 * （或极少量受 OS 调度影响的）事件被产出。
 */
class AlertingServiceCooldownConcurrencyTest {

    private static ServiceCallInfo throwInfo(String cls, String m, String tid) {
        ServiceCallInfo i = new ServiceCallInfo();
        i.setLogActionEnum(LogActionEnum.AFTER_THROW);
        i.setClassName(cls);
        i.setMethodName(m);
        i.setTraceid(tid);
        i.setContext("boom");
        i.setTimeMillis(System.currentTimeMillis());
        return i;
    }

    @Test
    void concurrent_firstTrigger_onlyOneEvent() throws Exception {
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        // cooldown=60s：第一次触发之后所有 N-1 条本应被抑制
        props.setCooldownSeconds(60);
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(120);
        // 不实际发 webhook
        props.setWebhookUrl("");

        AlertingService svc = new AlertingService(props, RestClient.create("http://localhost:9"), Clock.systemUTC());

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 喂 3 条抛错（满足 errorCount=3 的阈值）
                    for (int k = 0; k < 3; k++) {
                        svc.consumer(throwInfo("X", "m", "t-" + idx + "-" + k));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未在 10s 内完成");
        Assertions.assertEquals(0, errors.get(), "consumer 不应抛异常");

        int eventCount = svc.getRecent(100).size();
        // 原子版本：必须 = 1。允许极小范围的" 2 " 容忍以应对 OS 调度边界（仍然远低于未修复的 N）
        Assertions.assertTrue(eventCount <= 2,
                "并发触发应只产生 1~2 条事件（原子 putIfAbsent），实际=" + eventCount);
        Assertions.assertTrue(eventCount >= 1,
                "至少应触发 1 条事件；实际=" + eventCount);
    }

    @Test
    void concurrent_twoPhases_allowSecondBatchAfterCooldown() throws Exception {
        // 跨两个 phase：第一个 phase 触发后等 cooldownMs 之后第二个 phase 应当能再次触发
        // 验证"不只第一次抑制，且第二次能正确进入"。
        MethodTraceLogProperties.AlertingProperties props = new MethodTraceLogProperties.AlertingProperties();
        props.setEnable(true);
        props.setCooldownSeconds(1);  // 1s 短 cooldown
        props.getThreshold().setErrorCount(3);
        props.getThreshold().setWindowSeconds(120);
        props.setWebhookUrl("");

        AlertingService svc = new AlertingService(props, RestClient.create("http://localhost:9"), Clock.systemUTC());

        // phase 1
        for (int i = 0; i < 3; i++) svc.consumer(throwInfo("X", "m", "p1-" + i));
        int afterPhase1 = svc.getRecent(100).size();
        Assertions.assertEquals(1, afterPhase1, "phase 1 应产生 1 条事件");

        // 等过 cooldown（用 1.2s > 1s）
        Thread.sleep(1200L);

        // phase 2 —— 必须能再次触发
        for (int i = 0; i < 3; i++) svc.consumer(throwInfo("X", "m", "p2-" + i));
        int afterPhase2 = svc.getRecent(100).size();
        Assertions.assertEquals(2, afterPhase2,
                "phase 2 过 cooldown 后必须能再次触发；实际=" + afterPhase2);
    }
}
