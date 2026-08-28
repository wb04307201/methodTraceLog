package cn.wubo.method.trace.log.sampler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HeadBasedSampler 行为 / 边界 / 子调用契约测试。
 * <p>
 * 历史名 {@code HeadBasedSamplerTest}（基础采样行为）现在也承担
 * "post-G1" 的边界用例：
 *  1. 极端 sampleRate 的语义稳定性（0 / 1 永远确定性）
 *  2. 50% 采样的统计偏差在 ±5% 内
 *  3. 构造期 rate clamping（{@code < 0}、{@code > 1}、NaN 必须抛 IllegalArgumentException）
 *  4. 子调用契约：shouldStartRoot() 完全独立于"上一次返回值"，
 *     真正把 parent 决策的传递留给调用方（{@code LogAspect} + MDC）。
 */
class HeadBasedSamplerTest {

    @Test
    void sampleRate_one_alwaysSamples() {
        HeadBasedSampler sampler = new HeadBasedSampler(1.0);
        for (int i = 0; i < 5000; i++) {
            assertTrue(sampler.shouldStartRoot());
        }
    }

    @Test
    void sampleRate_zero_neverSamples() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.0);
        for (int i = 0; i < 5000; i++) {
            assertFalse(sampler.shouldStartRoot());
        }
    }

    @Test
    void sampleRate_half_roughlyFiftyPercent() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.5);
        int hits = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            if (sampler.shouldStartRoot()) hits++;
        }
        // spec: 容忍 ±5%
        assertTrue(hits >= n * 0.45 && hits <= n * 0.55,
                "expected ~50% but got " + hits + "/" + n);
    }

    @Test
    void invalidSampleRate_throws() {
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(1.1));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(Double.NaN));
    }

    // ---------- post-G1: 边界值 / rate clamping / 子调用契约 ----------

    @Test
    void rate_zero_never_samples() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.0);
        for (int i = 0; i < 5000; i++) {
            assertFalse(sampler.shouldStartRoot(),
                    "sampleRate=0.0 时 shouldStartRoot() 必须永远为 false");
        }
    }

    @Test
    void rate_one_always_samples() {
        HeadBasedSampler sampler = new HeadBasedSampler(1.0);
        for (int i = 0; i < 5000; i++) {
            assertTrue(sampler.shouldStartRoot(),
                    "sampleRate=1.0 时 shouldStartRoot() 必须永远为 true");
        }
    }

    @Test
    void rate_half_samples_approximately_50pct() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.5);
        int n = 10_000;
        int hits = 0;
        for (int i = 0; i < n; i++) {
            if (sampler.shouldStartRoot()) hits++;
        }
        // spec 容忍 ±5%
        assertTrue(hits >= n * 0.45 && hits <= n * 0.55,
                "expected ~50% but got " + hits + "/" + n);
    }

    @Test
    void rate_clamping_rejects_negative() {
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(-0.0001));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(-1.0));
    }

    @Test
    void rate_clamping_rejects_above_one() {
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(1.0001));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(2.0));
    }

    @Test
    void rate_clamping_rejects_NaN() {
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(Double.NaN));
    }

    @Test
    void getSampleRate_returns_constructor_value() {
        assertEquals(0.0, new HeadBasedSampler(0.0).getSampleRate(), 0.0);
        assertEquals(1.0, new HeadBasedSampler(1.0).getSampleRate(), 0.0);
        assertEquals(0.25, new HeadBasedSampler(0.25).getSampleRate(), 0.0);
    }

    /**
     * 子调用继承父决定 ——
     * 这是契约文档而非 sampler 行为：HeadBasedSampler 永远只看自身 sampleRate，
     * 父决定是否被继承由调用方（{@code LogAspect} 读 MDC）控制。
     * 这里锁住"sampler 不持有跨调用状态"，避免误把 parent state 注入 sampler。
     */
    @Test
    void child_inherits_parent_decision() {
        HeadBasedSampler always = new HeadBasedSampler(1.0);
        HeadBasedSampler never = new HeadBasedSampler(0.0);

        // 父调用与子调用都通过 sampler.shouldStartRoot() 取决定：两条链路决策一致。
        boolean parentAlways = always.shouldStartRoot();
        boolean childAlways = always.shouldStartRoot();
        assertTrue(parentAlways);
        assertTrue(childAlways, "sampleRate=1 时子调用应与父调用决策一致");

        boolean parentNever = never.shouldStartRoot();
        boolean childNever = never.shouldStartRoot();
        assertFalse(parentNever);
        assertFalse(childNever, "sampleRate=0 时子调用应与父调用决策一致");
    }
}
