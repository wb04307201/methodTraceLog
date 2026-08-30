package cn.wubo.method.trace.log.autoconfigure.otel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MtlSpanIdGenerator 的 OTel IdGenerator 契约测试。
 * <p>
 * - generateTraceId() 永远走随机（traceId 由 setParent(SpanContext) 控制）
 * - generateSpanId() 在 SpanIdContext 有 16 字符 hex 时返回它；否则随机 16 hex
 * - 随机结果必须：32 字符（trace）/16 字符（span）；字符集为小写 hex；多次调用去重
 */
class MtlSpanIdGeneratorTest {

    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");

    @AfterEach
    void cleanup() {
        SpanIdContext.clear();
    }

    @Test
    void generateTraceId_alwaysRandom32Hex() {
        MtlSpanIdGenerator gen = MtlSpanIdGenerator.INSTANCE;
        for (int i = 0; i < 50; i++) {
            String t = gen.generateTraceId();
            Assertions.assertNotNull(t);
            Assertions.assertEquals(32, t.length(), "traceId 必须 32 hex chars");
            Assertions.assertTrue(HEX32.matcher(t).matches(),
                    "traceId 必须全小写 hex；got: " + t);
        }
    }

    @Test
    void generateTraceId_randomDistinct() {
        // 概率上 50 次抽样不可能撞，但万一真撞了也算 bug（这里只 catch 永久 0）
        MtlSpanIdGenerator gen = MtlSpanIdGenerator.INSTANCE;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) ids.add(gen.generateTraceId());
        Assertions.assertTrue(ids.size() > 1, "50 次随机 traceId 不应全部相同");
    }

    @Test
    void generateSpanId_returnsContextValue_whenHex16() {
        MtlSpanIdGenerator gen = MtlSpanIdGenerator.INSTANCE;
        SpanIdContext.set("0123456789abcdef");
        Assertions.assertEquals("0123456789abcdef", gen.generateSpanId());
    }

    @Test
    void generateSpanId_fallsBackToRandom_whenContextIsWrongLength() {
        MtlSpanIdGenerator gen = MtlSpanIdGenerator.INSTANCE;
        SpanIdContext.set("tooshort");        // < 16 字符 → 不命中
        String a = gen.generateSpanId();
        Assertions.assertTrue(HEX16.matcher(a).matches(),
                "wrong-length context 应被忽略，返回随机 16 hex；got: " + a);
    }

    @Test
    void generateSpanId_fallsBackToRandom_whenContextIsNull() {
        MtlSpanIdGenerator gen = MtlSpanIdGenerator.INSTANCE;
        SpanIdContext.clear();
        for (int i = 0; i < 20; i++) {
            String s = gen.generateSpanId();
            Assertions.assertNotNull(s);
            Assertions.assertEquals(16, s.length());
            Assertions.assertTrue(HEX16.matcher(s).matches(),
                    "fallback spanId 必须全小写 hex；got: " + s);
        }
    }

    @Test
    void singletonInstance_isStable() {
        Assertions.assertSame(MtlSpanIdGenerator.INSTANCE, MtlSpanIdGenerator.INSTANCE,
                "INSTANCE 必须是单例");
    }
}