package cn.wubo.method.trace.log.analyze;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 {@code className#methodSignature} 的耗时聚合结果。
 * <p>
 * 分位数与 {@code max} 的单位是 Micrometer Timer 的 base time unit（默认纳秒 →
 * {@code HistogramSnapshot} 返回值以纳秒为准）。前端展示时自行换算。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlowMethodStats {

    /** 被调用类的全限定名（Timer tag {@code className}）。 */
    private String className;

    /** 方法签名（Timer tag {@code methodSignature}）。 */
    private String methodSignature;

    /** 采样到的调用次数。 */
    private long callCount;

    /** p50 耗时；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p50;

    /** p95 耗时；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p95;

    /** p99 耗时；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p99;

    /** 观察到的最大耗时。 */
    private double max;
}
