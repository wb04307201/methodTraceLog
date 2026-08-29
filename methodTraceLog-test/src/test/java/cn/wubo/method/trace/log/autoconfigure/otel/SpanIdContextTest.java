package cn.wubo.method.trace.log.autoconfigure.otel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SpanIdContext ThreadLocal 测试。
 * <p>
 * SpanIdContext 是 SimpleOtelServiceImpl 与 MtlSpanIdGenerator 之间的"约定通道"：
 *  - SimpleOtelServiceImpl.startSpan() 在生成 OTel span 之前 set() 一个 16 字符 hex
 *  - MtlSpanIdGenerator.generateSpanId() 优先读它，命中就直接返回；否则 fallback 随机
 * <p>
 * 这里锁住最基本的不变量：set/get/clear 在同一线程可见；clear 之后 get 返回 null；
 * 不串扰其它线程（ThreadLocal 语义）。
 */
class SpanIdContextTest {

    @AfterEach
    void cleanup() {
        SpanIdContext.clear();
    }

    @Test
    void set_then_get_returnsSameHex() {
        SpanIdContext.set("0123456789abcdef");
        Assertions.assertEquals("0123456789abcdef", SpanIdContext.get());
    }

    @Test
    void get_whenUnset_returnsNull() {
        SpanIdContext.clear();
        Assertions.assertNull(SpanIdContext.get());
    }

    @Test
    void clear_removesValue() {
        SpanIdContext.set("aabbccddeeff0011");
        Assertions.assertNotNull(SpanIdContext.get());
        SpanIdContext.clear();
        Assertions.assertNull(SpanIdContext.get());
    }

    @Test
    void threadLocal_doesNotLeakAcrossThreads() throws Exception {
        SpanIdContext.set("aaaaaaaaaaaaaaaa");
        Thread other = new Thread(() -> {
            // 其它线程看不到（ThreadLocal 隔离）
            Assertions.assertNull(SpanIdContext.get(),
                    "另一个线程不应看到主线程 set 的值");
        });
        other.start();
        other.join();

        // 主线程还在
        Assertions.assertEquals("aaaaaaaaaaaaaaaa", SpanIdContext.get());
    }

    @Test
    void set_acceptsAnyHexString() {
        // MtlSpanIdGenerator 仅在 length()==16 时使用；SpanIdContext 不做长度校验
        // —— 这是有意为之：调用方负责送合法值，单元测试锁住"不校验"的行为。
        SpanIdContext.set("short");
        Assertions.assertEquals("short", SpanIdContext.get());
        SpanIdContext.clear();

        SpanIdContext.set("verylongvalue");
        Assertions.assertEquals("verylongvalue", SpanIdContext.get());
    }
}