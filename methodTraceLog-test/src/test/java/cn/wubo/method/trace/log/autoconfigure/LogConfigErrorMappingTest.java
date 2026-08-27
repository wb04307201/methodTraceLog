package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

/**
 * 验证 LogConfig 的 router 错误映射过滤器：
 *  - IllegalArgumentException → 400
 *  - 任意 Exception           → 500 internal_error
 *  - ResponseStatusException   → 原样透传（已经表达了正确的 4xx）
 * <p>
 * 不启动 Spring 上下文，直接拿 LogConfig 上的 filter 走单元验证。
 */
class LogConfigErrorMappingTest {

    @Test
    void illegalArgument_mapsTo400() throws Exception {
        HandlerFunction<ServerResponse> failing = req -> {
            throw new IllegalArgumentException("bad input");
        };
        LogConfig cfg = new LogConfig();
        Method m = LogConfig.class.getDeclaredMethod("handleErrors", ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        ResponseStatusException rse = Assertions.assertThrows(
                ResponseStatusException.class,
                () -> invokeHandle(m, cfg, req("/x"), failing));
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, rse.getStatusCode());
        Assertions.assertNotNull(rse.getReason());
        Assertions.assertTrue(rse.getReason().contains("bad input"));
    }

    @Test
    void genericException_mapsTo500() throws Exception {
        HandlerFunction<ServerResponse> failing = req -> {
            throw new RuntimeException("boom");
        };
        LogConfig cfg = new LogConfig();
        Method m = LogConfig.class.getDeclaredMethod("handleErrors", ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        ResponseStatusException rse = Assertions.assertThrows(
                ResponseStatusException.class,
                () -> invokeHandle(m, cfg, req("/x"), failing));
        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rse.getStatusCode());
        Assertions.assertNotNull(rse.getReason());
        Assertions.assertTrue(rse.getReason().contains("internal_error"));
        Assertions.assertTrue(rse.getReason().contains("RuntimeException"));
    }

    @Test
    void responseStatusException_passesThrough() throws Exception {
        HandlerFunction<ServerResponse> failing = req -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found");
        };
        LogConfig cfg = new LogConfig();
        Method m = LogConfig.class.getDeclaredMethod("handleErrors", ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        ResponseStatusException rse = Assertions.assertThrows(
                ResponseStatusException.class,
                () -> invokeHandle(m, cfg, req("/x"), failing));
        Assertions.assertEquals(HttpStatus.NOT_FOUND, rse.getStatusCode());
        Assertions.assertEquals("trace not found", rse.getReason());
    }

    @Test
    void successfulHandler_isNotWrapped() throws Exception {
        HandlerFunction<ServerResponse> ok = req -> ServerResponse.ok().body("ok");
        LogConfig cfg = new LogConfig();
        Method m = LogConfig.class.getDeclaredMethod("handleErrors", ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        ServerResponse resp = (ServerResponse) m.invoke(cfg, req("/x"), ok);
        Assertions.assertEquals(HttpStatus.OK, HttpStatus.valueOf(resp.statusCode().value()));
    }

    @SuppressWarnings("unchecked")
    private static Object invokeHandle(Method m, LogConfig cfg, ServerRequest req, HandlerFunction<ServerResponse> hf) throws Exception {
        try {
            return m.invoke(cfg, req, hf);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // unwrap so the test sees the real exception thrown from inside
            throw (Exception) ite.getTargetException();
        }
    }

    private static ServerRequest req(String path) {
        MockHttpServletRequest mock = new MockHttpServletRequest("GET", path);
        return ServerRequest.create(mock, java.util.List.of(new org.springframework.http.converter.StringHttpMessageConverter()));
    }
}