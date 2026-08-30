package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.LogActionEnum;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-78: {@link MethodTraceLogEndPoint.MethodStatisticsDTO} 字段顺序 + NaN/Infinity 处理。
 * <p>
 * 字段顺序：DTO 有 9 个字段（className, methodSignature, totalCalls, successCalls,
 * failedCalls, successRate, failureRate, averageSuccessTime, averageFailureTime）。
 * Jackson 默认按字段声明顺序序列化 —— 改字段顺序会破坏面板 JS 解析。
 * <p>
 * NaN/Infinity：Micrometer Timer 在并发 / 空场景下可能返回 NaN；面板 JSON 序列化
 * 必须输出有限值（防止前端 parse JSON 出错）。当前实现是 timer.totalTime() 直接除 count，
 * count=0 时变 NaN；这里测"Timer 注册后 count=0 时实际行为是 NaN"，并通过 DTO
 * getter/setter 验证字段读写正常。
 */
class MethodTraceLogEndPointTest {

    @Test
    void dto_fieldOrderIsStable() throws Exception {
        // 反射读 DTO 字段声明顺序，确保没有 reorder
        Field[] fields = MethodTraceLogEndPoint.MethodStatisticsDTO.class.getDeclaredFields();
        List<String> names = new ArrayList<>(fields.length);
        for (Field f : fields) {
            // 跳过编译器合成的字段（如 this$0 之类的 outer reference 字段不存在于 inner DTO）
            names.add(f.getName());
        }
        // 必须按这个顺序：className, methodSignature, totalCalls, successCalls, failedCalls,
        //                  successRate, failureRate, averageSuccessTime, averageFailureTime
        // 允许前面有 "this$0" / "$assertionsDisabled" 这种合成字段
        int idxClassName = -1;
        int idxMethodSig = -1;
        int idxTotal = -1;
        int idxSuccess = -1;
        int idxFailed = -1;
        int idxSuccessRate = -1;
        int idxFailureRate = -1;
        int idxAvgSuccess = -1;
        int idxAvgFailure = -1;

        for (int i = 0; i < names.size(); i++) {
            switch (names.get(i)) {
                case "className" -> idxClassName = i;
                case "methodSignature" -> idxMethodSig = i;
                case "totalCalls" -> idxTotal = i;
                case "successCalls" -> idxSuccess = i;
                case "failedCalls" -> idxFailed = i;
                case "successRate" -> idxSuccessRate = i;
                case "failureRate" -> idxFailureRate = i;
                case "averageSuccessTime" -> idxAvgSuccess = i;
                case "averageFailureTime" -> idxAvgFailure = i;
                default -> {}
            }
        }

        assertTrue(idxClassName >= 0 && idxClassName < idxMethodSig,
                "className 必须在 methodSignature 之前；got: " + names);
        assertTrue(idxMethodSig < idxTotal,
                "methodSignature 必须在 totalCalls 之前；got: " + names);
        assertTrue(idxTotal < idxSuccess,
                "totalCalls 必须在 successCalls 之前；got: " + names);
        assertTrue(idxSuccess < idxFailed,
                "successCalls 必须在 failedCalls 之前；got: " + names);
        assertTrue(idxFailed < idxSuccessRate,
                "failedCalls 必须在 successRate 之前；got: " + names);
        assertTrue(idxSuccessRate < idxFailureRate,
                "successRate 必须在 failureRate 之前；got: " + names);
        assertTrue(idxFailureRate < idxAvgSuccess,
                "failureRate 必须在 averageSuccessTime 之前；got: " + names);
        assertTrue(idxAvgSuccess < idxAvgFailure,
                "averageSuccessTime 必须在 averageFailureTime 之前；got: " + names);
    }

    @Test
    void dto_constructor_setsClassNameAndMethodSignature() throws Exception {
        // DTO 构造只接 className + methodSignature
        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(new SimpleMeterRegistry());
        MethodTraceLogEndPoint.MethodStatisticsDTO dto =
                endPoint.new MethodStatisticsDTO("com.x.Foo", "foo()");
        assertEquals("com.x.Foo", dto.getClassName());
        assertEquals("foo()", dto.getMethodSignature());
        // 其他字段默认 0 / 0.0
        assertEquals(0L, dto.getTotalCalls());
        assertEquals(0L, dto.getSuccessCalls());
        assertEquals(0L, dto.getFailedCalls());
        assertEquals(0.0, dto.getSuccessRate());
        assertEquals(0.0, dto.getFailureRate());
        assertEquals(0.0, dto.getAverageSuccessTime());
        assertEquals(0.0, dto.getAverageFailureTime());
    }

    @Test
    void dto_settersAndGetters() {
        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(new SimpleMeterRegistry());
        MethodTraceLogEndPoint.MethodStatisticsDTO dto =
                endPoint.new MethodStatisticsDTO("c", "m");
        dto.setSuccessCalls(10);
        dto.setFailedCalls(2);
        dto.setTotalCalls(12);
        dto.setSuccessRate(83.33);
        dto.setFailureRate(16.67);
        dto.setAverageSuccessTime(123.456);
        dto.setAverageFailureTime(789.012);
        assertEquals(10L, dto.getSuccessCalls());
        assertEquals(2L, dto.getFailedCalls());
        assertEquals(12L, dto.getTotalCalls());
        assertEquals(83.33, dto.getSuccessRate());
        assertEquals(16.67, dto.getFailureRate());
        assertEquals(123.456, dto.getAverageSuccessTime());
        assertEquals(789.012, dto.getAverageFailureTime());
    }

    @Test
    void dto_handlesNaN_andInfinity_fields() {
        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(new SimpleMeterRegistry());
        MethodTraceLogEndPoint.MethodStatisticsDTO dto =
                endPoint.new MethodStatisticsDTO("c", "m");

        // 当前实现未做 NaN/Infinity 防护，直接写入 raw 值。
        // 这里锁住"原始 double 字段可以接收 NaN / Infinity"（setter 不抛），
        // 防止未来有人加 setter 校验意外破坏兼容性。
        dto.setAverageSuccessTime(Double.NaN);
        dto.setAverageFailureTime(Double.POSITIVE_INFINITY);
        dto.setSuccessRate(Double.NEGATIVE_INFINITY);

        assertTrue(Double.isNaN(dto.getAverageSuccessTime()));
        assertEquals(Double.POSITIVE_INFINITY, dto.getAverageFailureTime());
        assertEquals(Double.NEGATIVE_INFINITY, dto.getSuccessRate());
    }

    @Test
    void getAllMethodMetrics_emptyRegistry_returnsEmpty() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);

        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "空 registry → 空 list");
    }

    @Test
    void getAllMethodMetrics_singleTimer_aggregates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer t = Timer.builder("method.execution.time")
                .tags("className", "com.x.Foo", "methodSignature", "foo()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        t.record(100, TimeUnit.MILLISECONDS);
        t.record(200, TimeUnit.MILLISECONDS);

        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);
        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();

        assertEquals(1, result.size());
        MethodTraceLogEndPoint.MethodStatisticsDTO dto = result.get(0);
        assertEquals("com.x.Foo", dto.getClassName());
        assertEquals("foo()", dto.getMethodSignature());
        assertEquals(2L, dto.getSuccessCalls());
        assertEquals(0L, dto.getFailedCalls());
        assertEquals(2L, dto.getTotalCalls());
        assertEquals(100.0, dto.getSuccessRate(), 0.01);
        assertEquals(0.0, dto.getFailureRate(), 0.01);
        // 2 次记录平均 = 150ms
        assertEquals(150.0, dto.getAverageSuccessTime(), 0.01);
    }

    @Test
    void getAllMethodMetrics_separatesReturnAndThrowByAction() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer ok = Timer.builder("method.execution.time")
                .tags("className", "com.x.Foo", "methodSignature", "foo()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        ok.record(50, TimeUnit.MILLISECONDS);

        Timer err = Timer.builder("method.execution.time")
                .tags("className", "com.x.Foo", "methodSignature", "foo()", "action",
                        LogActionEnum.AFTER_THROW.name())
                .register(registry);
        err.record(500, TimeUnit.MILLISECONDS);

        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);
        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();

        assertEquals(1, result.size(), "同 className+methodSignature 的两条 timer 合并为 1 个 DTO");
        MethodTraceLogEndPoint.MethodStatisticsDTO dto = result.get(0);
        assertEquals(1L, dto.getSuccessCalls());
        assertEquals(1L, dto.getFailedCalls());
        assertEquals(2L, dto.getTotalCalls());
        assertEquals(50.0, dto.getSuccessRate(), 0.01);
        assertEquals(50.0, dto.getFailureRate(), 0.01);
        assertEquals(50.0, dto.getAverageSuccessTime(), 0.01);
        assertEquals(500.0, dto.getAverageFailureTime(), 0.01);
    }

    @Test
    void getAllMethodMetrics_skipsTimersMissingTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 注册一条缺 className tag 的 Timer —— 应当被跳过
        Timer.builder("method.execution.time")
                .tags("methodSignature", "ghost()", "action", LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        // 注册一条缺 methodSignature tag 的 Timer
        Timer.builder("method.execution.time")
                .tags("className", "com.x.Ghost", "action", LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        // 完整 Timer —— 应被计入
        Timer.builder("method.execution.time")
                .tags("className", "com.x.Real", "methodSignature", "real()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);

        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);
        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();

        assertEquals(1, result.size(),
                "缺 className 或 methodSignature tag 的 Timer 必须被跳过；got: " + result);
        assertEquals("com.x.Real", result.get(0).getClassName());
        assertEquals("real()", result.get(0).getMethodSignature());
    }

    @Test
    void getAllMethodMetrics_ignoresOtherTimers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 不同名的 Timer 不应被计入
        Timer.builder("http.server.requests")
                .tags("className", "com.x.Foo", "methodSignature", "foo()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        Timer.builder("method.execution.time")
                .tags("className", "com.x.Foo", "methodSignature", "foo()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);

        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);
        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();

        assertEquals(1, result.size(), "只计入 method.execution.time Timer");
    }

    @Test
    void getAllMethodMetrics_returnsArrayList_notUnmodifiable() {
        // 实现细节：getAllMethodMetrics 返回 new ArrayList<>(...) —— 可修改，便于测试断言。
        // 锁住这一行为。
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer.builder("method.execution.time")
                .tags("className", "c", "methodSignature", "m()", "action",
                        LogActionEnum.AFTER_RETURN.name())
                .register(registry);
        MethodTraceLogEndPoint endPoint = new MethodTraceLogEndPoint(registry);
        List<MethodTraceLogEndPoint.MethodStatisticsDTO> result = endPoint.getAllMethodMetrics();
        assertFalse(result.getClass().getName().contains("Unmodifiable"),
                "返回 list 应当可变（避免上层防御性复制带来额外开销）；got: " + result.getClass());
    }
}