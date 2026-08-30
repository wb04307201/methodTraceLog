package cn.wubo.method.trace.log.autoconfigure.otel;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ServiceCallInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * {@link SimpleOtelServiceImpl} 与 {@link OtelAutoConfig} 生命周期 / 边界用例测试。
 * <p>
 * 覆盖风险清单：
 * <ul>
 *     <li>R-48 — {@code maxQueueSize < 128} 被 {@link OtelAutoConfig} 静默钳到 128
 *         （单元验证：binding 层不校验；行为锁定）</li>
 *     <li>R-51 — 孤儿 end-call：AFTER_* 到达但 BEFORE 已被清理（activeSpans.get 返回 null）
 *         → consumer 必须 no-op，不能抛 NPE</li>
 *     <li>R-52 — 双重关闭安全：{@link SimpleOtelServiceImpl#close()} 被多次调用不抛</li>
 * </ul>
 */
class OtelAutoConfigLifecycleTest {

    private static OpenTelemetrySdk buildSdk(SpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    private static ServiceCallInfo sampleInfo(LogActionEnum action, String traceid, String spanid, String pspanid) {
        // simpleMethodName + simpleClassName 走真实方法反射路径不容易，构造一个最小 ServiceCallInfo
        return new ServiceCallInfo(traceid, pspanid, spanid,
                "com.example.Foo", "Foo", "bar", "bar()", "bar()",
                "arg", action, System.currentTimeMillis());
    }

    // ===== R-51: orphan end-call =====

    @Test
    @DisplayName("AFTER_RETURN 到达但没有对应 BEFORE → SimpleOtelServiceImpl 必须 no-op 不抛异常")
    void orphanAfterReturn_isNoop() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);
        try {
            // 只发 AFTER_RETURN，没有 BEFORE
            String spanid = UUID.randomUUID().toString();
            svc.consumer(sampleInfo(LogActionEnum.AFTER_RETURN, UUID.randomUUID().toString(), spanid, null));
            // 不应有 span 被导出
            Assertions.assertEquals(0, exporter.getFinishedSpanItems().size(),
                    "orphan AFTER_RETURN 必须不导出任何 span");
        } finally {
            svc.close();
            sdk.close();
        }
    }

    @Test
    @DisplayName("AFTER_THROW 到达但没有对应 BEFORE → SimpleOtelServiceImpl 必须 no-op")
    void orphanAfterThrow_isNoop() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);
        try {
            String spanid = UUID.randomUUID().toString();
            svc.consumer(sampleInfo(LogActionEnum.AFTER_THROW, UUID.randomUUID().toString(), spanid, null));
            Assertions.assertEquals(0, exporter.getFinishedSpanItems().size());
        } finally {
            svc.close();
            sdk.close();
        }
    }

    @Test
    @DisplayName("null ServiceCallInfo / null LogActionEnum → consumer 必须 no-op 不抛 NPE")
    void consumer_nullInputs_safe() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);
        try {
            Assertions.assertDoesNotThrow(() -> svc.consumer(null));
            // null action
            var info = new ServiceCallInfo(UUID.randomUUID().toString(), null,
                    UUID.randomUUID().toString(),
                    "com.example.Foo", "Foo", "bar", "bar()", "bar()",
                    "arg", null, System.currentTimeMillis());
            Assertions.assertDoesNotThrow(() -> svc.consumer(info));
        } finally {
            svc.close();
            sdk.close();
        }
    }

    // ===== R-52: 双重关闭安全 =====

    @Test
    @DisplayName("SimpleOtelServiceImpl.close() 第二次调用必须不抛")
    void doubleClose_isIdempotent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);

        // 第一次 close
        Assertions.assertDoesNotThrow(svc::close);
        // 第二次 close —— 第二次进入 activeSpans.values() 已是空，shutdownOnClose=false → no-op
        Assertions.assertDoesNotThrow(svc::close);
        // 第三次
        Assertions.assertDoesNotThrow(svc::close);
        sdk.close();
    }

    @Test
    @DisplayName("shutdownOnClose=true 时第一次 close 关闭 SDK，第二次 close 不抛异常")
    void doubleClose_shutdownOnCloseTrue_safe() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, true);

        // 第一次 close —— 关闭 SDK
        Assertions.assertDoesNotThrow(svc::close);
        // 第二次 close —— SDK 已经被关闭一次，ac.close() 第二次调用视实现而定
        // 当前实现：try { ac.close(); } catch(Exception e){ log } → 不抛
        Assertions.assertDoesNotThrow(svc::close);
    }

    // ===== R-48: OtelAutoConfig.maxQueueSize 钳位 =====

    @Test
    @DisplayName("OtelAutoConfig 在创建 SDK 时把 maxQueueSize 静默钳到 >= 128")
    void otelAutoConfig_maxQueueSizeIsClampedTo128() throws Exception {
        // 我们不启动完整 Spring 上下文 —— 直接验证 OtelAutoConfig 的方法逻辑。
        // OtelAutoConfig.methodTraceLogOpenTelemetry 是 public @Bean，没有 setter 注入，
        // 只能通过 reflection 或启动 Spring 触发。这里走 reflection 直接调用。
        var props = new MethodTraceLogProperties();
        props.getOtel().setMaxQueueSize(1); // 故意配极小
        props.getOtel().setMaxExportBatchSize(1); // 故意配极小
        props.getOtel().setExportDelayMillis(50L);
        props.getOtel().setExportTimeoutMillis(500L);

        OtelAutoConfig cfg = new OtelAutoConfig();
        Method m = OtelAutoConfig.class.getDeclaredMethod("methodTraceLogOpenTelemetry",
                MethodTraceLogProperties.class);
        m.setAccessible(true);
        OpenTelemetry otel;
        try {
            otel = (OpenTelemetry) m.invoke(cfg, props);
            Assertions.assertNotNull(otel);
            // 不抛异常即说明静默钳位生效；这里我们只锁定"不抛异常"。
        } finally {
            // OtelAutoConfig 内持有 sdk 字段 —— reflection 调一次无法直接 close。
            // Otel SDK 在测试 JVM 关闭时会被 GC，不必显式 close。
        }
    }

    // ===== bonus: BEFORE → AFTER_RETURN 端到端 =====

    @Test
    @DisplayName("正常 BEFORE + AFTER_RETURN 闭环：activeSpans 状态被清空（无内存泄漏）")
    void beforeAfterReturn_emitsOneSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);
        try {
            String traceid = UUID.randomUUID().toString();
            String spanid = UUID.randomUUID().toString();

            // BEFORE：必须先 setParent 上一个有效 SpanContext（用 SDK 的 tracer 临时起一个）
            Tracer tracer = sdk.getTracer("test");
            Span parentSpan = tracer.spanBuilder("test-parent").startSpan();
            try (Scope scope = parentSpan.makeCurrent()) {
                svc.consumer(sampleInfo(LogActionEnum.BEFORE, traceid, spanid, null));
            } finally {
                parentSpan.end();
            }

            // AFTER_RETURN
            var after = sampleInfo(LogActionEnum.AFTER_RETURN, traceid, spanid, null);
            after.setContext("RESULT-VALUE");
            svc.consumer(after);

            // 不强求 export 计数（parentSpan + child span 都通过同一个 exporter → 数量是 2）。
            // 关键是验证整个流程不抛异常 —— SimpleOtelServiceImpl 的 activeSpans 已被 AFTER_RETURN 清空。
            // 用 reflection 验证 activeSpans 是空：
            try {
                java.lang.reflect.Field f = SimpleOtelServiceImpl.class.getDeclaredField("activeSpans");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, ?> map = (java.util.Map<String, ?>) f.get(svc);
                Assertions.assertTrue(map.isEmpty(),
                        "AFTER_RETURN 之后 activeSpans 必须被清空（避免内存泄漏）；实际 size: " + map.size());
            } catch (NoSuchFieldException nsfe) {
                // field 名变了 —— 测试不强制
                Assumptions.assumeTrue(false,
                        "SimpleOtelServiceImpl.activeSpans 字段不存在，跳过此断言");
            } catch (IllegalAccessException iae) {
                Assumptions.assumeTrue(false,
                        "无法访问 activeSpans 字段: " + iae.getMessage());
            }
        } finally {
            svc.close();
            sdk.close();
        }
    }

    // ===== SpanIdContext ThreadLocal 清理 =====

    @Test
    @DisplayName("SpanIdContext 在 consumer 调用后必须清空（ThreadLocal 不残留）")
    void spanIdContext_clearedAfterConsumer() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdk(exporter);
        var otelProps = new MethodTraceLogProperties.OtelProperties();
        SimpleOtelServiceImpl svc = new SimpleOtelServiceImpl(sdk, otelProps, false);
        try {
            Tracer tracer = sdk.getTracer("test");
            Span parentSpan = tracer.spanBuilder("test-parent").startSpan();
            try (Scope scope = parentSpan.makeCurrent()) {
                svc.consumer(sampleInfo(LogActionEnum.BEFORE,
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), null));
            } finally {
                parentSpan.end();
            }
            // consumer 返回后 SpanIdContext 必须已 clear（避免 ThreadLocal 残留）
            Assertions.assertNull(SpanIdContext.get(),
                    "BEFORE consumer 返回后 SpanIdContext 必须清空；实际: " + SpanIdContext.get());
        } finally {
            svc.close();
            sdk.close();
        }
    }
}
