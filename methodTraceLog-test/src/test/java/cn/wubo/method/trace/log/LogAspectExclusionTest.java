package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@link LogAspect} 对 {@code method-trace-log.log.exclude-patterns} 黑名单的短路行为：
 * 命中方法名 → 直接 proceed()，不发任何 {@code ServiceCallInfo} 事件；
 * 未命中 → 正常发出 BEFORE + AFTER_RETURN 两个事件。
 * <p>
 * 匹配规则：方法名先 toLowerCase 再 equals 黑名单元素（即大小写不敏感、精确匹配）。
 * <p>
 * 注：Spring CGLIB 代理不会对 {@link Object#equals(Object)} / {@link Object#hashCode()}
 * / {@link Object#toString()} 应用 advice（Spring 设计上跳过 Object 方法），
 * 所以这里用 {@code lombokLikeEquals()} / {@code lombokLikeToString()} 等
 * "用户定义但语义上等价于 lombok 生成方法" 的方法作为代理目标。
 */
class LogAspectExclusionTest {

    /** 拦截 LogAspect 发出的一切事件，给测试断言用。 */
    private static final class CapturingCallService extends AbstractCallService {
        final List<ServiceCallInfo> captured = new CopyOnWriteArrayList<>();

        @Override
        public void consumer(ServiceCallInfo serviceCallInfo) {
            captured.add(serviceCallInfo);
        }

        @Override
        public String getCallServiceName() {
            return "CapturingCallService";
        }

        @Override
        public String getCallServiceDesc() {
            return "test capture for exclusion";
        }
    }

    /**
     * 让 {@link LogAspect} 能直接 wrap 的最小测试组件。
     * <p>
     * 包含若干用户定义的方法（方法名与 lombok / Data 生成方法同名），
     * 用来模拟 {@code equals/hashCode/toString/canEqual} 等高频样板方法。
     * 必须标注 {@link Component} 才能匹配 {@link LogAspect} 的 pointcut
     * （{@code @within(org.springframework.stereotype.Component)}）。
     */
    @Component
    static class ExcludeTarget {
        public String hello(String name) {
            return "hi-" + name;
        }

        /** 模拟 lombok @Data 生成的 equals，签名不同以避开 Object.equals 不被代理的限制。 */
        public boolean lombokLikeEquals(ExcludeTarget other) {
            return other != null;
        }

        /** 模拟 lombok @Data 生成的 toString。 */
        public String lombokLikeToString() {
            return "ExcludeTarget{}";
        }

        /** 模拟 lombok @Data 生成的 canEqual。 */
        public boolean canEqual(Object other) {
            return other instanceof ExcludeTarget;
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

    /**
     * 构造代理 + capture + 绑定到闭包外的容器，方便多个测试复用。
     */
    private static ExcludeTarget newProxy(List<String> excludePatterns, CapturingCallService capture) {
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(capture), new MethodTraceLogProperties());
        AspectJProxyFactory factory = new AspectJProxyFactory(new ExcludeTarget());
        factory.addAspect(new LogAspect(strategy, new HeadBasedSampler(1.0), excludePatterns));
        return factory.getProxy();
    }

    @Test
    void excluded_method_does_not_create_trace() {
        CapturingCallService capture = new CapturingCallService();
        ExcludeTarget proxy = newProxy(List.of("lombokLikeEquals"), capture);

        boolean result = proxy.lombokLikeEquals(new ExcludeTarget());

        assertEquals(true, result, "短路后 proceed() 应正常返回方法结果");
        assertEquals(0, capture.captured.size(),
                "lombokLikeEquals 应被黑名单短路，不发出任何事件");
    }

    @Test
    void non_excluded_method_creates_trace() {
        CapturingCallService capture = new CapturingCallService();
        ExcludeTarget proxy = newProxy(List.of("lombokLikeEquals"), capture);

        String result = proxy.hello("alice");

        assertEquals("hi-alice", result);
        assertEquals(2, capture.captured.size(),
                "非黑名单方法应发出 BEFORE + AFTER_RETURN 两个事件");
        assertEquals(LogActionEnum.BEFORE, capture.captured.get(0).getLogActionEnum());
        assertEquals(LogActionEnum.AFTER_RETURN, capture.captured.get(1).getLogActionEnum());
        assertEquals("hello", capture.captured.get(0).getMethodName());
    }

    @Test
    void case_insensitive_match() {
        CapturingCallService capture = new CapturingCallService();
        // 配置用大写（运行时从 "lombokLikeEquals" 转出），黑名单仍应匹配小写方法名
        String upper = "lombokLikeEquals".toUpperCase(java.util.Locale.ROOT);
        ExcludeTarget proxy = newProxy(List.of(upper), capture);

        proxy.lombokLikeEquals(new ExcludeTarget());

        assertEquals(0, capture.captured.size(),
                "黑名单应与 lombokLikeEquals 大小写无关地命中黑名单（uppercase=" + upper + "）");
    }

    @Test
    void empty_patterns_no_exclusion() {
        CapturingCallService capture = new CapturingCallService();
        ExcludeTarget proxy = newProxy(List.of(), capture);

        proxy.lombokLikeEquals(new ExcludeTarget());

        assertEquals(2, capture.captured.size(),
                "空黑名单下 lombokLikeEquals 也会被追踪（BEFORE + AFTER_RETURN）");
    }

    @Test
    void null_patterns_no_exclusion() {
        CapturingCallService capture = new CapturingCallService();
        ExcludeTarget proxy = newProxy(null, capture);

        proxy.lombokLikeEquals(new ExcludeTarget());

        assertEquals(2, capture.captured.size(),
                "null 黑名单下 lombokLikeEquals 也会被追踪（BEFORE + AFTER_RETURN）");
    }

    @Test
    void multi_pattern_one_matches_excludes() {
        CapturingCallService capture = new CapturingCallService();
        // 多模式命中：只要其中一个匹配就短路
        ExcludeTarget proxy = newProxy(List.of("foo", "canEqual", "bar"), capture);

        proxy.canEqual(new ExcludeTarget());

        assertEquals(0, capture.captured.size(),
                "黑名单含 canEqual 时，canEqual 命中短路");
    }
}