package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.context.TraceContextSnapshot;
import cn.wubo.method.trace.log.context.W3CTraceContextPropagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Trace context 入口过滤器：解析请求中的 {@code traceparent} 头，把上游的 traceId/parentId
 * 注入到当前线程的 MDC。配合 {@link LogAspect} 后，根调用的 pspanid 会自动成为上游的 spanId。
 * <p>
 * 不匹配任何路径（{@link #shouldNotFilter} 始终返回 false），意味着所有请求都会过。
 * 没有 traceparent 头的请求不受影响 —— MDC 留空，由 LogAspect 自己生成新的 traceid。
 */
@Slf4j
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceparent = request.getHeader(W3CTraceContextPropagator.TRACEPARENT_HEADER);
        TraceContextSnapshot snap = W3CTraceContextPropagator.extract(java.util.Map.of(W3CTraceContextPropagator.TRACEPARENT_HEADER, traceparent == null ? "" : traceparent));
        if (snap == null) {
            chain.doFilter(request, response);
            return;
        }
        try (AutoCloseable ignored = snap.restore()) {
            chain.doFilter(request, response);
        } catch (Exception e) {
            throw new ServletException("Failed to restore trace context", e);
        }
    }
}
