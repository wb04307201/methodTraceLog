package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.context.TraceContextSnapshot;
import cn.wubo.method.trace.log.context.W3CTraceContextPropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

/**
 * TraceContextRestTemplateInterceptor 的单元测试：MDC → HTTP traceparent 头的桥接。
 * <p>
 * 拦截器行为契约：
 *  <ul>
 *      <li>MDC 没值：不写 traceparent</li>
 *      <li>MDC 有值且请求没设 traceparent：写一次</li>
 *      <li>MDC 有值但请求已设 traceparent：不覆盖（用户优先级）</li>
 *      <li>MDC Sampled=false 时也要写出，带 flag 00（仅在 hasTrace() 时写）</li>
 *  </ul>
 * <p>
 * 本测试不依赖 Spring 容器，直接构造 MockClientHttpRequest 走拦截器。
 */
class TraceContextRestTemplateInterceptorTest {

    private final TraceContextRestTemplateInterceptor interceptor = new TraceContextRestTemplateInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static class CapturingExecution implements ClientHttpRequestExecution {
        @Override
        public ClientHttpResponse execute(org.springframework.http.HttpRequest request, byte[] body) {
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        }
    }

    @Test
    void noMdc_doesNotAddTraceparent() throws Exception {
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
        CapturingExecution exec = new CapturingExecution();

        interceptor.intercept(req, new byte[0], exec);

        Assertions.assertFalse(req.getHeaders().containsKey(W3CTraceContextPropagator.TRACEPARENT_HEADER),
                "MDC 为空时不应写 traceparent");
    }

    @Test
    void mdcPresent_addsTraceparentHeader() throws Exception {
        MDC.put(TraceContextSnapshot.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put(TraceContextSnapshot.MDC_SPAN_ID, "00f067aa0ba902b7");
        MDC.put(TraceContextSnapshot.MDC_SAMPLED, "true");

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
        CapturingExecution exec = new CapturingExecution();

        interceptor.intercept(req, new byte[0], exec);

        HttpHeaders hdr = req.getHeaders();
        Assertions.assertTrue(hdr.containsKey(W3CTraceContextPropagator.TRACEPARENT_HEADER),
                "MDC 有值时应写 traceparent");
        String tp = hdr.getFirst(W3CTraceContextPropagator.TRACEPARENT_HEADER);
        Assertions.assertNotNull(tp);
        Assertions.assertTrue(tp.contains("4bf92f3577b34da6a3ce929d0e0e4736"),
                "traceparent 应含当前 traceid；got: " + tp);
        Assertions.assertTrue(tp.contains("00f067aa0ba902b7"),
                "traceparent 应含当前 spanid 作为 parentId；got: " + tp);
        Assertions.assertTrue(tp.endsWith("-01"), "sampled=true → flag=01；got: " + tp);
    }

    @Test
    void existingTraceparentHeader_notOverwritten() throws Exception {
        MDC.put(TraceContextSnapshot.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put(TraceContextSnapshot.MDC_SPAN_ID, "00f067aa0ba902b7");
        MDC.put(TraceContextSnapshot.MDC_SAMPLED, "true");

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
        req.getHeaders().add(W3CTraceContextPropagator.TRACEPARENT_HEADER,
                "00-deadbeefdeadbeefdeadbeefdeadbeef-aaaabbbbaaaabbbb-01");

        interceptor.intercept(req, new byte[0], new CapturingExecution());

        String tp = req.getHeaders().getFirst(W3CTraceContextPropagator.TRACEPARENT_HEADER);
        Assertions.assertEquals(
                "00-deadbeefdeadbeefdeadbeefdeadbeef-aaaabbbbaaaabbbb-01",
                tp,
                "请求已设的 traceparent 必须保留；got: " + tp);
    }

    @Test
    void mdcSampledFalse_setsFlag00() throws Exception {
        MDC.put(TraceContextSnapshot.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put(TraceContextSnapshot.MDC_SPAN_ID, "00f067aa0ba902b7");
        MDC.put(TraceContextSnapshot.MDC_SAMPLED, "false");

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
        CapturingExecution exec = new CapturingExecution();

        interceptor.intercept(req, new byte[0], exec);

        String tp = req.getHeaders().getFirst(W3CTraceContextPropagator.TRACEPARENT_HEADER);
        Assertions.assertNotNull(tp);
        Assertions.assertTrue(tp.endsWith("-00"),
                "sampled=false → flag=00；got: " + tp);
    }
}