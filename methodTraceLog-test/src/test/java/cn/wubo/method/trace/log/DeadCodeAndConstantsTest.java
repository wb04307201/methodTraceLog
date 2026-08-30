package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-66 + R-75 锁字面量契约测试。
 * <ul>
 *     <li>R-66: {@code SampledDecision} 是死代码，已被删除。本测试断言
 *         {@code cn/wubo/method/trace/log/sampler/SampledDecision.class}
 *         在编译产物里不存在，{@link Constants} 的字面量也没提到它。</li>
 *     <li>R-75: Micrometer Timer 名称 + tag 键是公开契约（panel JS 也消费），
 *         必须保持字面量稳定。本测试锁住 {@link Constants} 的所有 public
 *         String 字段，并通过 {@link SimpleMonitorServiceImpl} 触发一次
 *         AFTER_RETURN / AFTER_THROW 事件，验证 Micrometer registry 里
 *         真有名字为 {@code method.execution.time} 且带 {@code className /
 *         methodSignature / action} tag 的 Timer。</li>
 * </ul>
 */
class DeadCodeAndConstantsTest {

    // ===== R-66: SampledDecision was removed as dead code =====

    @Test
    void sampledDecision_classFile_does_not_exist() {
        // 生产代码不再需要 SampledDecision —— 直接去编译产物目录里查。
        // target/classes 是 build 输出目录；如果有人误把它加回来，编译产物里
        // 必然有 .class 文件，本测试就会 fail。
        File classesDir = new File("target/classes/cn/wubo/method/trace/log/sampler/");
        if (!classesDir.exists()) {
            // 备选位置：模块根目录的 target
            classesDir = new File("../methodTraceLog/target/classes/cn/wubo/method/trace/log/sampler/");
        }
        if (!classesDir.exists()) {
            // 没找到编译产物目录 —— 用更宽松的断言：检查源代码 .java 已被删除
            File source = new File("../methodTraceLog/src/main/java/cn/wubo/method/trace/log/sampler/SampledDecision.java");
            assertFalse(source.exists(),
                    "SampledDecision.java 必须从 main 源码中删除（dead code）");
            return;
        }
        File classFile = new File(classesDir, "SampledDecision.class");
        assertFalse(classFile.exists(),
                "SampledDecision.class 必须是 dead code 不再出现在编译产物里；实际存在: " + classFile);
    }

    @Test
    void sampledDecision_source_file_does_not_exist() {
        File source = new File("../methodTraceLog/src/main/java/cn/wubo/method/trace/log/sampler/SampledDecision.java");
        assertFalse(source.exists(),
                "SampledDecision.java 必须从 main 源码中删除（dead code R-66）");
    }

    // ===== R-75: Constants 字面量契约 =====

    @Test
    void constants_className_isStable() {
        // panel JS 通过 /methodTraceLog/view/list 接口消费同一组 tag；改这里
        // 必须同步改前端。本测试锁住字面量。
        assertEquals("className", Constants.CLASS_NAME);
    }

    @Test
    void constants_methodSignature_isStable() {
        assertEquals("methodSignature", Constants.METHOD_SIGNATURE);
    }

    @Test
    void constants_methodExecutionTime_isStable() {
        assertEquals("method.execution.time", Constants.METHOD_EXECUTION_TIME);
    }

    @Test
    void constants_action_isStable() {
        assertEquals("action", Constants.ACTION);
    }

    @Test
    void timer_name_and_tags_match_constants() {
        // 触发一次 AFTER_RETURN，让 SimpleMonitorServiceImpl 注册一个
        // name=Constants.METHOD_EXECUTION_TIME, tags={className, methodSignature, action}
        // 的 Timer。然后断言 Micrometer registry 里真有这么一条 Timer。
        MeterRegistry registry = new SimpleMeterRegistry();
        SimpleMonitorServiceImpl svc = new SimpleMonitorServiceImpl(registry,
                new cn.wubo.method.trace.log.store.InMemoryTraceStore(), 8L * 60 * 60 * 1000L);

        ServiceCallInfo before = new ServiceCallInfo(
                "trace-r75", null, "span-r75",
                "Demo", "Demo", "work", "work()", "work()",
                "arg", LogActionEnum.BEFORE, System.currentTimeMillis());
        svc.consumer(before);

        ServiceCallInfo after = new ServiceCallInfo(
                "trace-r75", null, "span-r75",
                "Demo", "Demo", "work", "work()", "work()",
                "ok", LogActionEnum.AFTER_RETURN, System.currentTimeMillis());
        svc.consumer(after);

        // name 必须等于 Constants.METHOD_EXECUTION_TIME
        Timer found = registry.find(Constants.METHOD_EXECUTION_TIME).timer();
        assertNotNull(found,
                "AFTER_RETURN 后必须有 Timer name=Constants.METHOD_EXECUTION_TIME 存在");
        // tags 必须含 Constants.CLASS_NAME / Constants.METHOD_SIGNATURE / Constants.ACTION
        assertNotNull(found.getId().getTag(Constants.CLASS_NAME),
                "Timer 必须带 tag Constants.CLASS_NAME");
        assertNotNull(found.getId().getTag(Constants.METHOD_SIGNATURE),
                "Timer 必须带 tag Constants.METHOD_SIGNATURE");
        assertNotNull(found.getId().getTag(Constants.ACTION),
                "Timer 必须带 tag Constants.ACTION");

        // 同步计数对得上
        assertEquals(1L, found.count(),
                "AFTER_RETURN 一次后 Timer.count() 必须为 1");

        // 顺便触发 AFTER_THROW 路径，验证 timer 不重叠且 action 标签反映出来
        ServiceCallInfo before2 = new ServiceCallInfo(
                "trace-r75b", null, "span-r75b",
                "Demo", "Demo", "boom", "boom()", "boom()",
                "arg", LogActionEnum.BEFORE, System.currentTimeMillis());
        svc.consumer(before2);
        ServiceCallInfo after2 = new ServiceCallInfo(
                "trace-r75b", null, "span-r75b",
                "Demo", "Demo", "boom", "boom()", "boom()",
                "fail", LogActionEnum.AFTER_THROW, System.currentTimeMillis());
        svc.consumer(after2);

        Timer throwTimer = registry.find(Constants.METHOD_EXECUTION_TIME)
                .tag(Constants.ACTION, "AFTER_THROW").timer();
        assertNotNull(throwTimer,
                "AFTER_THROW 也必须有 Timer action=" + LogActionEnum.AFTER_THROW.name());
        assertEquals(1L, throwTimer.count(), "AFTER_THROW Timer count 应为 1");
    }

    @Test
    void constants_keys_are_unique() {
        // 防止有人意外复用同一字面量（如把 CLASS_NAME 设成 METHOD_SIGNATURE）。
        assertNotNull(Constants.CLASS_NAME);
        assertNotNull(Constants.METHOD_SIGNATURE);
        assertNotNull(Constants.METHOD_EXECUTION_TIME);
        assertNotNull(Constants.ACTION);
        assertDoesNotThrow(() -> {
            if (Constants.CLASS_NAME.equals(Constants.METHOD_SIGNATURE)) {
                throw new AssertionError("CLASS_NAME 与 METHOD_SIGNATURE 不应相同");
            }
            if (Constants.METHOD_SIGNATURE.equals(Constants.ACTION)) {
                throw new AssertionError("METHOD_SIGNATURE 与 ACTION 不应相同");
            }
        });
    }

    @Test
    void slowMethodAnalyzer_uses_methodExecutionTime_constant() {
        // R-75 的另一面：SlowMethodAnalyzer 同样依赖 Constants.METHOD_EXECUTION_TIME
        // 作为搜索 key。锁住"两者对同一字面量达成一致"，防止有人把分析器改成
        // 硬编码 "method.execution.time" 而 Constants 又被改成 "method.exec.time" 时漏改一处。
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = Timer.builder(Constants.METHOD_EXECUTION_TIME)
                .tags(new String[]{
                        Constants.CLASS_NAME, "C",
                        Constants.METHOD_SIGNATURE, "m()",
                        Constants.ACTION, "AFTER_RETURN"})
                .register(registry);
        timer.record(1, TimeUnit.MILLISECONDS);
        assertEquals(1L, timer.count());

        cn.wubo.method.trace.log.analyze.SlowMethodAnalyzer analyzer =
                new cn.wubo.method.trace.log.analyze.SlowMethodAnalyzer(registry);
        var stats = analyzer.analyze(5, 10);
        assertEquals(1, stats.size(),
                "SlowMethodAnalyzer 用 Constants.METHOD_EXECUTION_TIME 检索应找到上述 Timer");
        assertTrue(stats.get(0).getCallCount() >= 1);
    }

    @Test
    void counter_baseline_works() {
        // 一条轻量 sanity check：Counter/Timer 注册 API 没因为 R-75 的字面量改动而坏掉。
        MeterRegistry registry = new SimpleMeterRegistry();
        Counter c = registry.counter("sanity", "k", "v");
        c.increment();
        assertEquals(1.0, c.count());
    }
}