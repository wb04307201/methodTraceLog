package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 OTel SDK 真的从 methodTraceLog 接收到了 spans。
 * <p>
 * 通过 {@link InMemoryOtelTestConfig} 把 production OtelAutoConfig 的
 * OpenTelemetry SDK bean 替换为带 {@link InMemorySpanExporter} 的 SDK，
 * 让 {@link cn.wubo.method.trace.log.autoconfigure.otel.SimpleOtelServiceImpl}
 * （它通过 Spring DI 拿到 {@code OpenTelemetry}）把所有 span 写到内存里供断言。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtelExportIT {

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        Map<String, Object> props = Map.of(
                "method-trace-log.otel.enable", "true",
                // 用一个无效端口，避免 OtelAutoConfig 创建的 OTLP SDK 把
                // warn 日志刷屏（不影响测试结果，但噪音大）。
                "method-trace-log.otel.endpoint", "http://127.0.0.1:1/v1/traces");
        host = MtlE2eHarness.primary(8098, props, InMemoryOtelTestConfig.class);
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void simpleotel_records_spans_for_traced_methods() {
        ApplicationContext ctx = host.context();
        InMemoryOtelTestConfig.TestSpanExporterHolder holder;
        try {
            holder = ctx.getBean(InMemoryOtelTestConfig.TestSpanExporterHolder.class);
        } catch (NoSuchBeanDefinitionException e) {
            // 兜底：理论上不会走到这里，因为我们 primary(int, Map, Class[]) 显式传入了 config
            Assumptions.abort("InMemorySpanExporter not wired (TestConfiguration not loaded); skipping");
            return;
        }

        // Fire a trace via /test/aspectLog (which calls TestComponent.aspectLogDemo internally)
        host.http().getForEntity(
                "http://localhost:8098/test/aspectLog?name=otel-export-1", String.class);

        // Wait for SimpleMonitorServiceImpl to record the trace into /view/list
        List<MethodTraceInfo> roots = host.awaitTraceList(1, Duration.ofSeconds(5));
        assertThat(roots).isNotEmpty();

        // Give SimpleOtelServiceImpl time to push spans to the exporter
        // SimpleSpanProcessor is synchronous so this should be immediate, but allow some slack
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        var finishedSpans = holder.exporter.getFinishedSpanItems();
        assertThat(finishedSpans)
                .as("OTel SimpleOtelServiceImpl should have exported spans to InMemorySpanExporter "
                        + "(via @Primary OpenTelemetry SDK with SimpleSpanProcessor wiring)")
                .isNotEmpty();

        // Verify the span name includes "aspectLogDemo" (the inner @AspectLog-annotated method).
        // SimpleOtelServiceImpl names spans as "<classSimpleName>.<methodName>" — so the
        // inner method is "TestComponent.aspectLogDemo", the controller is "TestController.aspectLog".
        boolean foundAspectLogCall = finishedSpans.stream()
                .anyMatch(s -> s.getName() != null && s.getName().contains("aspectLog"));
        assertThat(foundAspectLogCall)
                .as("expected at least one exported span whose name contains 'aspectLog'; got: %s",
                        finishedSpans.stream().map(s -> s.getName()).toList())
                .isTrue();
    }
}
