package cn.wubo.method.trace.log.sampler;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 头采样器：按固定概率决定是否对根调用进行采样。
 * <p>
 * 配置：
 *  - sampleRate = 1.0  → 全部采样（默认）
 *  - sampleRate = 0.0  → 全部丢弃
 *  - sampleRate = 0.1  → 大约 10% 的根调用被采样
 * <p>
 * 线程安全：使用 {@link ThreadLocalRandom} 避免共享 Random 的争用。
 */
public class HeadBasedSampler implements Sampler {

    private final double sampleRate;

    public HeadBasedSampler(double sampleRate) {
        if (sampleRate < 0.0 || sampleRate > 1.0 || Double.isNaN(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be in [0.0, 1.0], got: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    @Override
    public boolean shouldStartRoot() {
        if (sampleRate >= 1.0) {
            return true;
        }
        if (sampleRate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    public double getSampleRate() {
        return sampleRate;
    }
}
