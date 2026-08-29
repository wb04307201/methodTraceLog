package cn.wubo.method.trace.log.analyze;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 {@code className#methodSignature} 的耗时聚合结果。
 * <p>
 * <b>单位说明</b>：{@code p50} / {@code p95} / {@code p99} / {@code max} 的单位是
 * <b>纳秒（nanoseconds）</b>，与 Micrometer {@code Timer} 在 {@code SimpleMeterRegistry}
 * 上的默认 base time unit 一致 —— {@code HistogramSnapshot} 的 percentile / mean /
 * max 都以 base unit 返回，实测对 250ms 采样返回 {@code 2.5E8} = 250,000,000 ns。
 * <p>
 * 调用方展示毫秒时记得除以 1_000_000（{@code ms = ns / 1_000_000}）。
 * <p>
 * 未开 percentile histogram 时 p50/p95/p99 回落到 {@code mean()}（同一个纳秒单位）。
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

    /** p50 耗时（纳秒）；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p50;

    /** p95 耗时（纳秒）；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p95;

    /** p99 耗时（纳秒）；Timer 未开 percentile histogram 时回落为 mean。 */
    private double p99;

    /** 观察到的最大耗时（纳秒）。 */
    private double max;
}
