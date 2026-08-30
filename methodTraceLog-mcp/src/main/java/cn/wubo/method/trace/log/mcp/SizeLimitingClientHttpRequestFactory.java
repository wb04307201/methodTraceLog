package cn.wubo.method.trace.log.mcp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

/**
 * A {@link ClientHttpRequestFactory} wrapper that enforces a maximum body size on the response.
 * <p>
 * Two-stage enforcement:
 * <ol>
 *   <li><b>Content-Length pre-check</b> — if the response advertises a {@code Content-Length}
 *       greater than the cap, the response is closed immediately and a
 *       {@link ResponseTooLargeException} is thrown without buffering any body bytes.</li>
 *   <li><b>Stream guard</b> — for chunked transfers (or lying {@code Content-Length} headers)
 *       the response body is wrapped in a {@link BoundedInputStream} that counts bytes as they
 *       pass through and throws {@link ResponseTooLargeException} the moment the count exceeds
 *       the cap. This prevents OOM even if the host omits or under-states {@code Content-Length}.</li>
 * </ol>
 * This is used by the MCP module to cap log downloads at 16 MiB without bringing in
 * {@code org.springframework.web.reactive.function.client.ExchangeStrategies}.
 *
 * <p>The factory does not modify the request side of the wire — it only intercepts response
 * handling. Connect-time and read-time timeouts remain the responsibility of the underlying
 * factory (typically {@link org.springframework.http.client.JdkClientHttpRequestFactory}).
 */
public class SizeLimitingClientHttpRequestFactory implements ClientHttpRequestFactory {

    /**
     * Signals that a response body exceeded the configured maximum size. Caught by
     * {@link MethodTraceLogMcpService#classifyErrorCode} and surfaced as the structured
     * error code {@code RESPONSE_TOO_LARGE}.
     */
    public static class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public ResponseTooLargeException(String message) {
            super(message);
        }
    }

    private final ClientHttpRequestFactory delegate;
    private final long maxResponseSizeBytes;

    /**
     * @param delegate            the underlying factory that produces the real requests.
     * @param maxResponseSizeBytes the maximum response body size in bytes (must be {@code > 0}).
     */
    public SizeLimitingClientHttpRequestFactory(ClientHttpRequestFactory delegate, long maxResponseSizeBytes) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (maxResponseSizeBytes <= 0) throw new IllegalArgumentException("maxResponseSizeBytes must be > 0");
        this.delegate = delegate;
        this.maxResponseSizeBytes = maxResponseSizeBytes;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        return new SizeLimitingRequest(delegate.createRequest(uri, httpMethod), maxResponseSizeBytes);
    }

    long getMaxResponseSizeBytes() {
        return maxResponseSizeBytes;
    }

    /** Visible for tests. */
    ClientHttpRequestFactory getDelegate() {
        return delegate;
    }

    /**
     * Wraps a request so its {@link ClientHttpRequest#execute()} returns a {@link SizeLimitingResponse}.
     */
    static class SizeLimitingRequest implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final long maxBytes;

        SizeLimitingRequest(ClientHttpRequest delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override public HttpMethod getMethod() { return delegate.getMethod(); }
        @Override public URI getURI() { return delegate.getURI(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
        @Override public OutputStream getBody() throws IOException { return delegate.getBody(); }

        @Override
        public ClientHttpResponse execute() throws IOException {
            return new SizeLimitingResponse(delegate.execute(), maxBytes);
        }
    }

    /**
     * Drop-in replacement for Spring's response that swaps the raw body stream for
     * {@link BoundedInputStream}. We override only the response-side surface that Spring's
     * RestClient invocation path actually calls.
     */
    static class SizeLimitingResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final long maxBytes;
        private InputStream body;
        private boolean closed;

        SizeLimitingResponse(ClientHttpResponse delegate, long maxBytes) throws IOException {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            // 1) Content-Length pre-check
            HttpHeaders headers = delegate.getHeaders();
            long declared = -1;
            if (headers != null) {
                String cl = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
                if (cl != null) {
                    try {
                        declared = Long.parseLong(cl.trim());
                    } catch (NumberFormatException ignored) {
                        // ignore; rely on stream guard
                    }
                }
            }
            if (declared > maxBytes) {
                try { delegate.close(); } catch (Exception ignored) { /* best effort */ }
                throw new ResponseTooLargeException(
                        "Declared Content-Length " + declared + " bytes exceeds limit " + maxBytes + " bytes");
            }
            // 2) Stream guard is installed lazily in getBody()
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = new BoundedInputStream(delegate.getBody(), maxBytes);
            }
            return body;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try { if (body != null) body.close(); } catch (IOException ignored) { }
                delegate.close();
            }
        }
    }

    /**
     * Counts bytes flowing through. Throws {@link ResponseTooLargeException} when the count
     * exceeds the cap. Stops counting once the cap is reached so the stream can be closed
     * cleanly by the caller without double-throwing.
     */
    static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long count;
        private boolean exceeded;

        BoundedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        private void check() throws IOException {
            if (exceeded) return;
            if (count > maxBytes) {
                exceeded = true;
                throw new ResponseTooLargeException(
                        "Response body exceeded limit of " + maxBytes + " bytes (after " + count + " bytes)");
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                count++;
                check();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
                check();
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) {
                count += skipped;
                check();
            }
            return skipped;
        }

        @Override
        public void close() throws IOException {
            try { in.close(); } finally { super.close(); }
        }
    }
}
