package cn.wubo.method.trace.log.sampler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeadBasedSamplerTest {

    @Test
    void sampleRate_one_alwaysSamples() {
        HeadBasedSampler sampler = new HeadBasedSampler(1.0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(sampler.shouldStartRoot());
        }
    }

    @Test
    void sampleRate_zero_neverSamples() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.0);
        for (int i = 0; i < 1000; i++) {
            assertFalse(sampler.shouldStartRoot());
        }
    }

    @Test
    void sampleRate_half_roughlyFiftyPercent() {
        HeadBasedSampler sampler = new HeadBasedSampler(0.5);
        int hits = 0;
        int n = 10000;
        for (int i = 0; i < n; i++) {
            if (sampler.shouldStartRoot()) hits++;
        }
        // 允许 ±3% 抖动
        assertTrue(hits > n * 0.47 && hits < n * 0.53, "expected ~50% but got " + hits + "/" + n);
    }

    @Test
    void invalidSampleRate_throws() {
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(1.1));
        assertThrows(IllegalArgumentException.class, () -> new HeadBasedSampler(Double.NaN));
    }
}
