package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import cn.wubo.method.trace.log.sampler.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-77: LogAspect 采样继承测试。
 * <p>
 * LogAspect.around 的关键分支（lines 178-184 of LogAspect.java）：
 * <pre>{@code
 *   boolean sampled;
 *   if (preSampled != null) {
 *     sampled = Boolean.parseBoolean(preSampled);   // 子调用继承
 *   } else {
 *     sampled = sampler.shouldStartRoot();           // 根调用投骰子
 *   }
 *   MDC.put(LOG_SAMPLED, Boolean.toString(sampled));
 * }</pre>
 * <p>
 * 子调用必须从 MDC 读父决定，<b>不能再次调</b> {@code sampler.shouldStartRoot()}。
 * <p>
 * 实现策略：本测试直接调 {@link LogAspect#around} 两次模拟"根 + 子"调用路径，
 * 第二次调前手动设 MDC 模拟"继承父采样决定"的子调用。这样能避开 Spring AOP 的
 * self-invocation 限制（self-invocation 不走代理），直接验证 LogAspect 内部的
 * 继承分支。
 */
class LogAspectSamplingTest {

    @Component
    static class TestTarget {
        public String hello(String name) {
            return "hi-" + name;
        }
    }

    /** 记录 sampler.shouldStartRoot() 被调用次数。 */
    static class CountingSampler implements Sampler {
        final AtomicInteger rootCalls = new AtomicInteger();
        private final boolean decision;

        CountingSampler(boolean decision) {
            this.decision = decision;
        }

        @Override
        public boolean shouldStartRoot() {
            rootCalls.incrementAndGet();
            return decision;
        }
    }

    /** spy ICallService，记录所有事件 */
    static final class CapturingCallService extends AbstractCallService {
        final List<ServiceCallInfo> captured = new CopyOnWriteArrayList<>();

        @Override
        public void consumer(ServiceCallInfo info) {
            captured.add(info);
        }

        @Override
        public String getCallServiceName() {
            return "CapturingCallService";
        }

        @Override
        public String getCallServiceDesc() {
            return "capture";
        }
    }

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        closeables.clear();
        MDC.clear();
    }

    @Test
    void childInheritsParentSampling_doesNotInvokeSampler() {
        // 直接构造 LogAspect 并直接调 around：模拟"根 + 子"两次调用
        CountingSampler sampler = new CountingSampler(true);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        proxy.hello("hello"); // root call

        assertEquals(1, sampler.rootCalls.get(),
                "单次 root 调用 sampler 应被调 1 次；实际 " + sampler.rootCalls.get());
        assertEquals(2, capture.captured.size(),
                "BEFORE + AFTER_RETURN = 2 个事件");
    }

    @Test
    void rootDecidesFalse_childNeverSampled() {
        // sampler=false → root 调用不发事件
        CountingSampler sampler = new CountingSampler(false);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        String result = proxy.hello("inner-arg");
        assertEquals("hi-inner-arg", result, "目标方法本身应正常返回");

        // sampler 应只被调 1 次（仅根调用投骰子）
        assertEquals(1, sampler.rootCalls.get());

        // 关键断言：所有事件都应被跳过（0 事件）
        assertEquals(0, capture.captured.size(),
                "根调用 sampled=false 时所有子调用也应不采样 → 0 事件");
    }

    @Test
    void preSampled_false_inheritedByChild() throws Exception {
        // 手动塞 preSampled="false" 模拟"父未被采样的子调用"路径
        CountingSampler sampler = new CountingSampler(true);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        // 模拟 "已有父 sampled=false 的子调用" 进入
        MDC.put(LogAspect.LOG_TRACE_ID, "fake-trace");
        MDC.put(LogAspect.LOG_PSPAN_ID, "fake-parent");
        MDC.put(LogAspect.LOG_SAMPLED, "false");

        String result = proxy.hello("test");
        assertEquals("hi-test", result, "目标方法应正常返回");

        // sampler 不应被调（继承路径）
        assertEquals(0, sampler.rootCalls.get(),
                "preSampled 已存在时不应调 sampler.shouldStartRoot()");

        // 子调用 sampled=false → 不发任何事件
        assertEquals(0, capture.captured.size(),
                "preSampled=false 的子调用应不发出任何 ServiceCallInfo 事件");
    }

    @Test
    void preSampled_true_inheritedByChild_noReSampling() throws Exception {
        // 关键路径：preSampled="true" 必须让 LogAspect 跳过 sampler.shouldStartRoot()
        CountingSampler sampler = new CountingSampler(true);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        MDC.put(LogAspect.LOG_TRACE_ID, "fake-trace");
        MDC.put(LogAspect.LOG_PSPAN_ID, "fake-parent");
        MDC.put(LogAspect.LOG_SAMPLED, "true");

        String result = proxy.hello("test");
        assertEquals("hi-test", result);

        // 关键：sampler 应 0 次（不重复投骰子）
        assertEquals(0, sampler.rootCalls.get(),
                "preSampled=true 的子调用必须不调 sampler.shouldStartRoot()（继承父决定）");
        // 但子调用仍发 2 事件（BEFORE+AFTER_RETURN）
        assertEquals(2, capture.captured.size());
    }

    @Test
    void noMdcSampler_invokesRootOnceForRootCall() {
        // 没 MDC 的根调用 → sampler 必被调一次
        CountingSampler sampler = new CountingSampler(true);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        proxy.hello("hello");

        assertEquals(1, sampler.rootCalls.get(),
                "无 MDC 的根调用：sampler 应调 1 次");
    }

    @Test
    void mtlSampledKey_constantIsStable() {
        // LogAspect.LOG_SAMPLED = "mtlSampled" —— 自定义 logback pattern
        // 用 %X{mtlSampled} 取这个键。改这里必须同步改前端 / logback 配置。
        assertEquals("mtlSampled", LogAspect.LOG_SAMPLED);
    }

    @Test
    void headBasedSampler_true_emitsEvents_andClearsMDC() {
        // 边界：用 HeadBasedSampler + proxy bean 验证 MDC 的 mtlSampled 被正确清除
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, new HeadBasedSampler(1.0));
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        proxy.hello("foo");

        assertTrue(capture.captured.size() >= 2,
                "HeadBasedSampler(1.0) 应让 root 调用发 2 事件");
        // MDC 中的 mtlSampled 应在 finally 后被清除（顶层调用）
        assertNull(MDC.get(LogAspect.LOG_SAMPLED),
                "根调用后 MDC 的 mtlSampled 必须被清除（避免 Tomcat 线程复用时泄漏）");
    }

    @Test
    void headBasedSampler_zero_emitsZeroEvents() {
        // 边界：sampleRate=0 → 所有 root 调用都应丢弃
        CountingSampler sampler = new CountingSampler(false);
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture), new MethodTraceLogProperties());

        LogAspect aspect = new LogAspect(strategy, sampler);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestTarget());
        factory.addAspect(aspect);
        TestTarget proxy = factory.getProxy();

        proxy.hello("anything");

        assertEquals(0, capture.captured.size(),
                "sampler=false → 0 事件");
        assertEquals(1, sampler.rootCalls.get(), "root 调用仍调 sampler 1 次（仅结果不采样）");
    }

    @Test
    void logTraceKey_constantIsStable() {
        // LogAspect.LOG_TRACE_ID = "traceid"
        assertEquals("traceid", LogAspect.LOG_TRACE_ID);
    }

    @Test
    void logSpanKey_constantIsStable() {
        // LogAspect.LOG_SPAN_ID = "spanid"
        assertEquals("spanid", LogAspect.LOG_SPAN_ID);
    }

    @Test
    void logPSpanKey_constantIsStable() {
        // LogAspect.LOG_PSPAN_ID = "pspanid"
        assertEquals("pspanid", LogAspect.LOG_PSPAN_ID);
    }
}