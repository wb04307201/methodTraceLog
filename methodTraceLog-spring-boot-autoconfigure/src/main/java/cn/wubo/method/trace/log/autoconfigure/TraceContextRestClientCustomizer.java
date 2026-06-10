package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.context.TraceContextSnapshot;
import cn.wubo.method.trace.log.context.W3CTraceContextPropagator;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * 给 Spring Boot 默认的 RestClient.Builder 注入 traceparent 头。
 * 用户使用 builder.build() 出来的 RestClient 发请求时会自动带上当前线程的 trace 上下文。
 */
public class TraceContextRestClientCustomizer implements RestClientCustomizer {

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            TraceContextSnapshot snap = TraceContextSnapshot.capture();
            if (snap.hasTrace()) {
                String tp = W3CTraceContextPropagator.build(snap.getTraceid(), snap.getSpanid(), "true".equals(snap.getSampled()));
                HttpHeaders headers = request.getHeaders();
                if (!headers.containsKey(W3CTraceContextPropagator.TRACEPARENT_HEADER)) {
                    headers.add(W3CTraceContextPropagator.TRACEPARENT_HEADER, tp);
                }
            }
            return execution.execute(request, body);
        });
    }
}
