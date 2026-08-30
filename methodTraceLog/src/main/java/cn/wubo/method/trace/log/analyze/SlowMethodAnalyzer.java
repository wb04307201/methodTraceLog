package cn.wubo.method.trace.log.analyze;

import cn.wubo.method.trace.log.Constants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 从 Micrometer registry 里把 {@code method.execution.time} 的 Timer 聚合成"最慢方法 topN"。
 * <p>
 * <b>关于 percentile：</b>Micrometer 的 Timer 默认只维护 count / sum / max，不维护
 * percentile histogram，此时 {@link HistogramSnapshot#percentileValues()} 返回空数组。
 * 为了让端点在默认配置下也返回有意义的数字，本实现在拿不到分位数时<b>回落到
 * {@link HistogramSnapshot#mean()}</b>，并把 p50/p95/p99 都填成该均值。业务侧若需要真实
 * 分位数，注册 Timer 时加 {@code .publishPercentiles(0.5, 0.95, 0.99)} 即可，本类会自动优先取用。
 * <p>
 * <b>关于 windowMinutes：</b>Micrometer Timer 是进程内累计值，无法按任意时间窗回溯。
 * 该参数目前仅做入参校验（≤0 直接返回空），保留给后续接入 step-based registry 用。
 */
@Slf4j
public class SlowMethodAnalyzer {

    /** 目标 percentile，与 {@link SlowMethodStats} 的 p50/p95/p99 一一对应。 */
    private static final double[] PERCENTILES = {0.5, 0.95, 0.99};

    /** 匹配 percentile 时允许的浮点误差。 */
    private static final double PERCENTILE_EPSILON = 1e-6;

    private final MeterRegistry registry;

    public SlowMethodAnalyzer(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 聚合当前 registry 里所有方法耗时 Timer，按 p99 降序取前 topN。
     *
     * @param windowMinutes 时间窗（分钟）。必须 &gt; 0，否则返回空 list；见类注释说明其当前语义
     * @param topN          最多返回条数，必须 &gt; 0，否则返回空 list
     * @return 按 p99 降序（p99 相同则按 max 降序）的统计列表，永不为 null
     */
    public List<SlowMethodStats> analyze(int windowMinutes, int topN) {
        if (windowMinutes <= 0 || topN <= 0) return List.of();

        Collection<Timer> timers = registry.find(Constants.METHOD_EXECUTION_TIME).timers();
        List<SlowMethodStats> stats = new ArrayList<>(timers.size());
        for (Timer t : timers) {
            String cls = t.getId().getTag(Constants.CLASS_NAME);
            String sig = t.getId().getTag(Constants.METHOD_SIGNATURE);
            if (cls == null || sig == null) continue;

            HistogramSnapshot snap = t.takeSnapshot();
            double mean = sanitize(snap.mean());
            double p50 = percentileOrFallback(snap, PERCENTILES[0], mean);
            double p95 = percentileOrFallback(snap, PERCENTILES[1], mean);
            double p99 = percentileOrFallback(snap, PERCENTILES[2], mean);
            double max = sanitize(snap.max());

            stats.add(new SlowMethodStats(cls, sig, snap.count(), p50, p95, p99, max));
        }
        // p99 降序；p99 相同（例如全部回落到 mean）时用 max 兜底，保证顺序确定
        stats.sort(Comparator.comparingDouble(SlowMethodStats::getP99)
                .thenComparingDouble(SlowMethodStats::getMax)
                .reversed());
        return new ArrayList<>(stats.subList(0, Math.min(topN, stats.size())));
    }

    /**
     * 从快照里取指定 percentile 的值；Timer 未开 histogram（数组为空）或该 percentile
     * 未注册时回落到 {@code fallback}。
     *
     * @param snap       Timer 快照
     * @param percentile 目标分位，如 0.99
     * @param fallback   取不到时的回落值（本类传 mean）
     * @return 分位数值或回落值
     */
    private static double percentileOrFallback(HistogramSnapshot snap, double percentile, double fallback) {
        ValueAtPercentile[] values = snap.percentileValues();
        if (values == null || values.length == 0) return fallback;
        for (ValueAtPercentile v : values) {
            if (Math.abs(v.percentile() - percentile) < PERCENTILE_EPSILON) {
                return sanitize(v.value());
            }
        }
        return fallback;
    }

    /**
     * 把 NaN / 无穷 归一成 0，避免 JSON 序列化出非法数字。
     *
     * @param d 原始值
     * @return 有限值本身，否则 0
     */
    private static double sanitize(double d) {
        return Double.isFinite(d) ? d : 0d;
    }
}
