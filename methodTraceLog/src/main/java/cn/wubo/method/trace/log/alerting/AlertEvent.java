package cn.wubo.method.trace.log.alerting;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条告警事件。既是 ring buffer 的元素，也是 webhook POST 的 JSON body。
 * <p>
 * {@link #type} 目前只有 {@code "error_threshold"}（滑动窗口错误数越界），
 * 预留 {@code "slow_method"} 给慢方法告警。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent {

    /** 事件唯一 id（UUID），便于下游去重。 */
    private String alertId;

    /** 事件产生时间（epoch millis）。 */
    private long timestamp;

    /** 事件类型：{@code error_threshold}（预留 {@code slow_method}）。 */
    private String type;

    /** 触发告警的类全限定名。 */
    private String className;

    /** 触发告警的方法名。 */
    private String methodName;

    /** 触发这次越界的那一条调用的 traceid，便于跳到调用链详情。 */
    private String traceId;

    /** 窗口内累计的错误数（即越界时的窗口大小）。 */
    private int errorCount;

    /** 滑动窗口长度（秒），与配置一致，方便下游展示"60 秒内 3 次"。 */
    private long windowSeconds;

    /** 截断后的异常信息样本（最多 500 字符）。 */
    private String sampleError;
}
