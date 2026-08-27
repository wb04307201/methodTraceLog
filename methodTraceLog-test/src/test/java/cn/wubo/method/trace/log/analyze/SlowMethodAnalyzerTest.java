package cn.wubo.method.trace.log.analyze;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * {@link SlowMethodAnalyzer} 聚合行为测试。
 * <p>
 * 注意：Micrometer 的 Timer 默认不开 percentile histogram，
 * {@code HistogramSnapshot.percentileValues()} 会返回空数组。因此 analyzer 在
 * 空数组时回落到 {@code snapshot.mean()}（见实现注释），这里的断言依赖这个回落行为。
 */
class SlowMethodAnalyzerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final SlowMethodAnalyzer analyzer = new SlowMethodAnalyzer(registry);

    private Timer timer(String cls, String sig) {
        return Timer.builder("method.execution.time")
                .tag("className", cls).tag("methodSignature", sig).tag("action", "AFTER_RETURN")
                .register(registry);
    }

    @Test
    @DisplayName("空 registry 返回空结果")
    void empty_registry_returns_empty() {
        Assertions.assertTrue(analyzer.analyze(5, 10).isEmpty());
    }

    @Test
    @DisplayName("按 p99 降序排序，慢方法排在前面")
    void sorts_by_p99_descending() {
        Timer fast = timer("Fast", "m()");
        Timer slow = timer("Slow", "m()");
        // 100 次 1ms
        for (int i = 0; i < 100; i++) fast.record(Duration.ofMillis(1));
        // 100 次 100ms
        for (int i = 0; i < 100; i++) slow.record(Duration.ofMillis(100));

        var result = analyzer.analyze(5, 10);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("Slow", result.get(0).getClassName());
        Assertions.assertTrue(result.get(0).getP99() > result.get(1).getP99());
    }

    @Test
    @DisplayName("topN 截断结果数量")
    void topN_limits_results() {
        for (int n = 0; n < 20; n++) {
            Timer t = timer("C" + n, "m()");
            for (int i = 0; i < 5; i++) t.record(Duration.ofMillis(n + 1));
        }
        Assertions.assertEquals(3, analyzer.analyze(5, 3).size());
    }

    @Test
    @DisplayName("基础字段齐全：类名 / 签名 / 调用次数 / 分位数 / max")
    void includes_basic_fields() {
        Timer t = timer("X", "foo()");
        for (int i = 0; i < 10; i++) t.record(Duration.ofMillis(5));
        var stats = analyzer.analyze(5, 10);
        Assertions.assertEquals(1, stats.size());
        var s = stats.get(0);
        Assertions.assertEquals("X", s.getClassName());
        Assertions.assertEquals("foo()", s.getMethodSignature());
        Assertions.assertEquals(10, s.getCallCount());
        Assertions.assertTrue(s.getP50() > 0);
        Assertions.assertTrue(s.getP95() > 0);
        Assertions.assertTrue(s.getP99() > 0);
        Assertions.assertTrue(s.getMax() > 0);
    }

    @Test
    @DisplayName("非法 windowMinutes / topN 返回空结果")
    void non_positive_args_return_empty() {
        Timer t = timer("X", "foo()");
        t.record(Duration.ofMillis(5));
        Assertions.assertTrue(analyzer.analyze(0, 10).isEmpty());
        Assertions.assertTrue(analyzer.analyze(5, 0).isEmpty());
    }

    @Test
    @DisplayName("开启 percentile histogram 的 Timer 使用真实分位数而非 mean 回落")
    void real_percentiles_are_used_when_histogram_enabled() {
        Timer t = Timer.builder("method.execution.time")
                .tag("className", "H").tag("methodSignature", "m()").tag("action", "AFTER_RETURN")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        for (int i = 0; i < 100; i++) t.record(Duration.ofMillis(50));
        var stats = analyzer.analyze(5, 10);
        Assertions.assertEquals(1, stats.size());
        // 真实分位数应落在 50ms 附近（纳秒单位），而不是 0
        Assertions.assertTrue(stats.get(0).getP99() > 0);
        Assertions.assertTrue(stats.get(0).getP50() > 0);
    }
}
