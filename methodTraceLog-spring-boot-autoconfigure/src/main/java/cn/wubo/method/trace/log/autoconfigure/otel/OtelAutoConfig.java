package cn.wubo.method.trace.log.autoconfigure.otel;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * OpenTelemetry 桥接自动配置。
 * <p>
 * 仅当同时满足：
 *  - classpath 上有 opentelemetry-sdk
 *  - 配置 method-trace-log.otel.enable=true
 * 才注册 OTLP HTTP 导出器和 SimpleOtelServiceImpl。
 * <p>
 * 不会与 Spring Boot 自带的 OpenTelemetry 配置冲突（如果存在），因为
 * 我们自己构建 SDK 并不注入到全局 OpenTelemetry。
 */
@Slf4j
@AutoConfiguration(after = cn.wubo.method.trace.log.autoconfigure.LogConfig.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.OpenTelemetrySdk")
@ConditionalOnProperty(name = "method-trace-log.otel.enable", havingValue = "true")
public class OtelAutoConfig {

    private OpenTelemetrySdk openTelemetrySdk;
    private SimpleOtelServiceImpl otelService;

    @Bean
    public OpenTelemetry methodTraceLogOpenTelemetry(MethodTraceLogProperties properties) {
        MethodTraceLogProperties.OtelProperties otel = properties.getOtel();
        Resource resource = buildResource(otel);

        OtlpHttpSpanExporterBuilder exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(otel.getEndpoint())
                .setTimeout(java.time.Duration.ofMillis(Math.max(1000L, otel.getExportTimeoutMillis())));

        BatchSpanProcessorBuilder bspBuilder = BatchSpanProcessor.builder(exporterBuilder.build())
                .setScheduleDelay(java.time.Duration.ofMillis(Math.max(100L, otel.getExportDelayMillis())))
                .setMaxQueueSize(Math.max(128, otel.getMaxQueueSize()))
                .setMaxExportBatchSize(Math.max(16, otel.getMaxExportBatchSize()));
        SpanProcessor bsp = bspBuilder.build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(bsp)
                .setIdGenerator(MtlSpanIdGenerator.INSTANCE)
                .setResource(resource)
                .build();

        this.openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        log.info("mtl-otel: OTLP HTTP exporter ready, endpoint={} service={}", otel.getEndpoint(), otel.getServiceName());
        return this.openTelemetrySdk;
    }

    @Bean
    public SimpleOtelServiceImpl simpleOtelService(OpenTelemetry openTelemetry, MethodTraceLogProperties properties) {
        this.otelService = new SimpleOtelServiceImpl(openTelemetry, properties.getOtel(), true);
        return this.otelService;
    }

    @Bean
    public DisposableBean otelShutdownHook() {
        return () -> {
            if (otelService != null) {
                otelService.close();
            }
            if (openTelemetrySdk != null) {
                openTelemetrySdk.close();
            }
        };
    }

    private static Resource buildResource(MethodTraceLogProperties.OtelProperties otel) {
        io.opentelemetry.sdk.resources.ResourceBuilder builder = Resource.getDefault().toBuilder();
        builder.put("service.name", otel.getServiceName() == null ? "method-trace-log" : otel.getServiceName());
        if (otel.getServiceNamespace() != null && !otel.getServiceNamespace().isEmpty()) {
            builder.put("service.namespace", otel.getServiceNamespace());
        }
        return builder.build();
    }
}
