package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import cn.wubo.method.trace.log.sampler.Sampler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * LogConfig.mtlSampler 对 NaN / Infinity 边界值的回归测试（F-08）。
 * <p>
 * 修复前：{@code Math.max(0.0, Math.min(1.0, rate))} 在 NaN 上行为未定义：
 * Math.min 看到 NaN 返回 NaN，Math.max 看到 NaN 返回 NaN，结果 rate=NaN。
 * HeadBasedSampler 构造器对 NaN 抛 IllegalArgumentException（见
 * {@code HeadBasedSamplerTest} 的"非法值抛 IAE"断言），整个 Spring 上下文启动失败。
 * <p>
 * 修复后：用 {@code Double.isFinite} 先把 NaN / Infinity 排除，落到 1.0 兜底。
 * <p>
 * 本测试直接反射调用 {@code mtlSampler} 方法（不启动 Spring 容器），喂入 NaN /
 * Infinity / 越界值，断言构造不抛且 sampler 行为正确。
 */
class LogConfigSampleRateNaNTest {

    @Test
    void nan_sampleRate_fallsBackTo1dot0() throws Exception {
        MethodTraceLogProperties props = newProps(Double.NaN);
        Sampler sampler = invoke(props);
        Assertions.assertNotNull(sampler);
        Assertions.assertTrue(sampler instanceof HeadBasedSampler);
        // 行为校验：rate=1.0 → shouldStartRoot 始终返回 true
        for (int i = 0; i < 100; i++) {
            Assertions.assertTrue(sampler.shouldStartRoot(), "NaN fallback 到 1.0 应始终采样");
        }
    }

    @Test
    void positiveInfinity_fallsBackTo1dot0() throws Exception {
        MethodTraceLogProperties props = newProps(Double.POSITIVE_INFINITY);
        Sampler sampler = invoke(props);
        Assertions.assertNotNull(sampler);
        for (int i = 0; i < 100; i++) {
            Assertions.assertTrue(sampler.shouldStartRoot(), "+Inf fallback 到 1.0 应始终采样");
        }
    }

    @Test
    void negativeInfinity_fallsBackTo1dot0() throws Exception {
        MethodTraceLogProperties props = newProps(Double.NEGATIVE_INFINITY);
        Sampler sampler = invoke(props);
        Assertions.assertNotNull(sampler);
        for (int i = 0; i < 100; i++) {
            Assertions.assertTrue(sampler.shouldStartRoot(), "-Inf fallback 到 1.0 应始终采样");
        }
    }

    @Test
    void normal_values_stillClamp() throws Exception {
        // 越界值仍要 clamp 到 [0, 1]（不被新的 NaN 路径影响）
        MethodTraceLogProperties pOver = newProps(1.5);
        Sampler sOver = invoke(pOver);
        Assertions.assertNotNull(sOver);
        // 1.0 clamped → shouldStartRoot 始终 true
        for (int i = 0; i < 50; i++) {
            Assertions.assertTrue(sOver.shouldStartRoot(), "rate=1.5 应 clamp 到 1.0");
        }

        MethodTraceLogProperties pUnder = newProps(-0.5);
        Sampler sUnder = invoke(pUnder);
        // 0.0 clamped → shouldStartRoot 始终 false
        for (int i = 0; i < 50; i++) {
            Assertions.assertFalse(sUnder.shouldStartRoot(), "rate=-0.5 应 clamp 到 0.0");
        }
    }

    private static MethodTraceLogProperties newProps(Double sampleRate) {
        MethodTraceLogProperties p = new MethodTraceLogProperties();
        if (sampleRate != null) {
            p.getLog().setSampleRate(sampleRate);
        }
        return p;
    }

    private static Sampler invoke(MethodTraceLogProperties p) throws Exception {
        LogConfig cfg = new LogConfig();
        Method m = LogConfig.class.getDeclaredMethod("mtlSampler", MethodTraceLogProperties.class);
        m.setAccessible(true);
        return (Sampler) m.invoke(cfg, p);
    }
}
