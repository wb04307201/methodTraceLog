package cn.wubo.method.trace.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link LogAspect} 在异常路径上同时写入 {@code context}（stringified）和
 * {@code rawException}（真正的 {@link Throwable}）。回归 fix(otel)：fix 之前
 * {@code context} 已被 transContext(e) 字符串化，{@code recordException()} 拿不到对象。
 */
class LogAspectExceptionTest {

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
            return "test capture";
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
     * 把 {@link TestComponent} 包到 AspectJ 代理里，代理里装一个能捕获所有事件
     * 的 {@link ICallService}，再调一个肯定抛异常的方法，最后断言发出去的
     * {@code AFTER_THROW} 事件同时带 {@code context}（字符串）和
     * {@code rawException}（Throwable 实例）。
     */
    @Test
    void afterThrow_should_set_both_stringifiedContext_and_rawException() {
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(
                List.of(capture),
                new MethodTraceLogProperties());

        AspectJProxyFactory factory = new AspectJProxyFactory(new TestComponent());
        factory.addAspect(new LogAspect(strategy));
        TestComponent proxy = factory.getProxy();

        // internalImplMethodThrowing 总是抛，且 @AspectLog("renamedThrowing") 已生效
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> proxy.internalImplMethodThrowing("oops"));

        // BEFORE + AFTER_THROW 两事件都该被捕获
        assertEquals(2, capture.captured.size(), "应触发 BEFORE + AFTER_THROW 两个事件");
        ServiceCallInfo before = capture.captured.get(0);
        ServiceCallInfo afterThrow = capture.captured.get(1);

        assertEquals(LogActionEnum.BEFORE, before.getLogActionEnum());
        assertEquals(LogActionEnum.AFTER_THROW, afterThrow.getLogActionEnum());

        // BEFORE 阶段不应有 rawException
        assertEquals(null, before.getRawException());

        // AFTER_THROW：context 被 transContext(e) 转成 string（不是 Throwable）
        Object ctx = afterThrow.getContext();
        assertNotNull(ctx, "AFTER_THROW 的 context 必须存在");
        assertTrue(ctx instanceof String,
                "context 应已被 transContext(e) 字符串化，实际类型: " + ctx.getClass());
        assertTrue(((String) ctx).contains("renamedThrowing"),
                "context 字符串应包含原始异常消息");

        // AFTER_THROW：rawException 必须是真正的 Throwable 且就是被抛出的那个对象
        Throwable raw = afterThrow.getRawException();
        assertNotNull(raw, "AFTER_THROW 的 rawException 不能为空（这是 fix 的核心点）");
        assertTrue(raw instanceof RuntimeException,
                "rawException 应是 RuntimeException，实际: " + raw.getClass());
        assertSame(thrown, raw,
                "rawException 应是同一被抛出异常对象，供 OTel recordException() 使用");
        assertTrue(raw.getMessage().contains("renamedThrowing"));
    }
}