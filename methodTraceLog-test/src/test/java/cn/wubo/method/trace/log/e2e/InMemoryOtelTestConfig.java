package cn.wubo.method.trace.log.e2e;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用 @TestConfiguration：把 production OtelAutoConfig 创建的 OpenTelemetry
 * SDK（默认 OTLP/HTTP 导出器）替换为带 InMemorySpanExporter 的 SDK。
 * <p>
 * 关键：{@code @Primary} 让 Spring DI 在 SimpleOtelServiceImpl 构造时优先选
 * 这里的 SDK，{@link InMemorySpanExporter} 实例作为 {@code SpanExporter} bean
 * 同时提供给测试用例直接拿。
 * <p>
 * 注：production OtelAutoConfig 仍然会创建自己的 SDK bean（OTLP/HTTP 导出器），
 * 但因为我们这里标注了 {@code @Primary}，Spring 不会重复注入到 SimpleOtelServiceImpl。
 * 那个 SDK 也会启动并尝试向 OTLP 端点发空请求（日志里有 warn，但不影响测试）。
 */
@TestConfiguration
public class InMemoryOtelTestConfig {

    /** 测试用例通过 Spring 上下文拿到这个 bean，从而读出 SimpleOtelServiceImpl 写出的 spans。 */
    public static class TestSpanExporterHolder {
        public final InMemorySpanExporter exporter;

        public TestSpanExporterHolder(InMemorySpanExporter exporter) {
            this.exporter = exporter;
        }
    }

    @Bean
    public InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    @Primary
    public OpenTelemetry testOpenTelemetry(InMemorySpanExporter inMemorySpanExporter) {
        // 使用 SimpleSpanProcessor：每条 span 立即 flush，避免 BatchSpanProcessor
        // 的延迟让测试 race。SimpleOtelServiceImpl 走我们的 SDK → 我们看到 spans。
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(inMemorySpanExporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Bean
    public TestSpanExporterHolder testSpanExporterHolder(InMemorySpanExporter inMemorySpanExporter) {
        return new TestSpanExporterHolder(inMemorySpanExporter);
    }
}
