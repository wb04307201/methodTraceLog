package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.context.TraceContextSnapshot;
import cn.wubo.method.trace.log.context.W3CTraceContextPropagator;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 给 RestTemplate 注入 traceparent 头。
 * 注册方式：{@code restTemplate.setInterceptors(Collections.singletonList(new TraceContextRestTemplateInterceptor()))}
 */
public class TraceContextRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        TraceContextSnapshot snap = TraceContextSnapshot.capture();
        if (snap.hasTrace() && !request.getHeaders().containsKey(W3CTraceContextPropagator.TRACEPARENT_HEADER)) {
            String tp = W3CTraceContextPropagator.build(snap.getTraceid(), snap.getSpanid(), "true".equals(snap.getSampled()));
            request.getHeaders().add(W3CTraceContextPropagator.TRACEPARENT_HEADER, tp);
        }
        return execution.execute(request, body);
    }
}
