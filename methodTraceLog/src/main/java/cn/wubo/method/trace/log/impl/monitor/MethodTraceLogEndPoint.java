package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.LogActionEnum;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import lombok.Data;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Endpoint(id = "methodtrace")
public class MethodTraceLogEndPoint {

    private final MeterRegistry meterRegistry;

    public MethodTraceLogEndPoint(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

        /**
     * 获取所有方法的执行统计信息。
     * <p>
     * 该方法通过查询注册表中的 Timer 指标，收集所有标记为 "method.execution.time" 的方法执行时间数据，
     * 并根据类名和方法签名进行分组统计。统计内容包括成功调用次数、失败调用次数、平均成功时间、
     * 平均失败时间、总调用次数以及成功率和失败率。
     *
     * @return 包含所有方法统计信息的 DTO 列表，每个 DTO 对应一个唯一的方法（由类名和方法签名标识）
     */
    @ReadOperation
    public List<MethodStatisticsDTO> getAllMethodMetrics() {
        // 获取所有方法执行时间的 Timer
        Collection<Timer> timers = Search.in(meterRegistry)
                .name("method.execution.time")
                .timers();

        // 按类名和方法签名分组统计
        Map<String, MethodStatisticsDTO> metricsMap = new HashMap<>();

        for (Timer timer : timers) {
            String className = timer.getId().getTag("className");
            String methodSignature = timer.getId().getTag("methodSignature");
            String action = timer.getId().getTag("action");

            if (className == null || methodSignature == null) {
                continue;
            }

            String key = className + "#" + methodSignature;
            MethodStatisticsDTO stats = metricsMap.computeIfAbsent(key,
                    k -> new MethodStatisticsDTO(className, methodSignature));

            long count = timer.count();
            double totalTime = timer.totalTime(TimeUnit.MILLISECONDS);

            if (LogActionEnum.AFTER_RETURN.name().equals(action)) {
                stats.setSuccessCalls(stats.getSuccessCalls() + count);
                if (count > 0) {
                    stats.setAverageSuccessTime(totalTime / count);
                }
            } else if (LogActionEnum.AFTER_THROW.name().equals(action)) {
                stats.setFailedCalls(stats.getFailedCalls() + count);
                if (count > 0) {
                    stats.setAverageFailureTime(totalTime / count);
                }
            }
        }

        // 计算总调用次数和成功率/失败率
        for (MethodStatisticsDTO stats : metricsMap.values()) {
            long total = stats.getSuccessCalls() + stats.getFailedCalls();
            stats.setTotalCalls(total);

            if (total > 0) {
                stats.setSuccessRate((double) stats.getSuccessCalls() / total * 100);
                stats.setFailureRate((double) stats.getFailedCalls() / total * 100);
            }
        }

        return new ArrayList<>(metricsMap.values());
    }


    @Data
    public class MethodStatisticsDTO {
        private String className;
        private String methodSignature;
        private long totalCalls;
        private long successCalls;
        private long failedCalls;
        private double successRate;
        private double failureRate;
        private double averageSuccessTime;
        private double averageFailureTime;

        // 构造函数
        public MethodStatisticsDTO(String className, String methodSignature) {
            this.className = className;
            this.methodSignature = methodSignature;
        }
    }
}
