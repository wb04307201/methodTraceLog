package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-83: {@link LogConfig#handleErrors} 把任意 {@code Exception} 包装成
 * {@code ResponseStatusException(500, "internal_error: " + e.getClass().getSimpleName())}。
 * <p>
 * 与 {@link ErrorMessagePropertiesPostProcessor} 配合下，前端能从 500 响应 body 中
 * 看到具体的异常类名（而非通用 "Internal Server Error"）。
 * <p>
 * 本测试锁住该契约：reason 格式 = {@code "internal_error: <SimpleName>"}。
 */
class LogConfigErrorDetailTest {

    @Test
    void exceptionClassNameAppearsInReason() throws Exception {
        // 自定义异常类型，验证 getSimpleName() 被传递
        HandlerFunction<ServerResponse> failing = req -> {
            throw new CustomMtlException("synthetic failure for R-83");
        };
        Method m = LogConfig.class.getDeclaredMethod("handleErrors",
                ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        LogConfig cfg = new LogConfig();

        ResponseStatusException rse;
        try {
            throw (Exception) ((java.lang.reflect.InvocationTargetException) m.invoke(cfg,
                    req("/x"), failing)).getCause();
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertTrue(cause instanceof ResponseStatusException,
                    "handleErrors 必须抛 ResponseStatusException；实际 " + cause);
            rse = (ResponseStatusException) cause;
        }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rse.getStatusCode());
        assertNotNull(rse.getReason());
        assertTrue(rse.getReason().contains("internal_error"),
                "reason 必须以 'internal_error' 开头；实际: " + rse.getReason());
        assertTrue(rse.getReason().contains("CustomMtlException"),
                "reason 必须含 e.getClass().getSimpleName()；实际: " + rse.getReason());
    }

    @Test
    void runtimeExceptionClassNameAppearsInReason() throws Exception {
        HandlerFunction<ServerResponse> failing = req -> {
            throw new IllegalStateException("state mismatch");
        };
        Method m = LogConfig.class.getDeclaredMethod("handleErrors",
                ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);

        try {
            m.invoke(new LogConfig(), req("/x"), failing);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertTrue(cause instanceof ResponseStatusException);
            ResponseStatusException rse = (ResponseStatusException) cause;
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rse.getStatusCode());
            assertTrue(rse.getReason().contains("IllegalStateException"),
                    "IllegalStateException 的 simpleName 必须出现在 reason；实际: " + rse.getReason());
            assertTrue(rse.getReason().contains("internal_error"));
        }
    }

    @Test
    void nullPointerException_classNameInReason() throws Exception {
        HandlerFunction<ServerResponse> failing = req -> {
            throw new NullPointerException("npe");
        };
        Method m = LogConfig.class.getDeclaredMethod("handleErrors",
                ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);

        try {
            m.invoke(new LogConfig(), req("/x"), failing);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertTrue(cause instanceof ResponseStatusException);
            ResponseStatusException rse = (ResponseStatusException) cause;
            assertTrue(rse.getReason().contains("NullPointerException"),
                    "NPE 的 simpleName 必须出现；实际: " + rse.getReason());
        }
    }

    @Test
    void underlyingCauseIsChainedInRse() throws Exception {
        // R-83 副断言：原异常作为 cause 链入 ResponseStatusException，
        // 便于 ErrorAttributes 链上追溯
        IllegalArgumentException original = new IllegalArgumentException("origin");
        HandlerFunction<ServerResponse> failing = req -> {
            throw original;
        };
        // 注：IllegalArgumentException 在 handleErrors 里被映射为 400，
        // 不是 500。所以本测试单独走 ResponseStatusException 的 cause 链。

        // 改用普通 RuntimeException 触发 500 路径
        RuntimeException runtimeEx = new RuntimeException("rt", original);
        HandlerFunction<ServerResponse> failing2 = req -> {
            throw runtimeEx;
        };

        Method m = LogConfig.class.getDeclaredMethod("handleErrors",
                ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);
        try {
            m.invoke(new LogConfig(), req("/x"), failing2);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            ResponseStatusException rse = (ResponseStatusException) ite.getCause();
            assertNotNull(rse.getCause());
            assertEquals(runtimeEx, rse.getCause(),
                    "原 RuntimeException 必须链入 ResponseStatusException 的 cause；"
                            + "ErrorMessagePropertiesPostProcessor 仅展示 reason，但日志需要完整链");
        }
    }

    @Test
    void reason_formatIsInternalErrorColonClassName() throws Exception {
        // 锁住字面量契约：reason 格式 = "internal_error: <SimpleName>"
        HandlerFunction<ServerResponse> failing = req -> {
            throw new ArrayIndexOutOfBoundsException("aiob");
        };
        Method m = LogConfig.class.getDeclaredMethod("handleErrors",
                ServerRequest.class, HandlerFunction.class);
        m.setAccessible(true);

        try {
            m.invoke(new LogConfig(), req("/x"), failing);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            ResponseStatusException rse = (ResponseStatusException) ite.getCause();
            String reason = rse.getReason();
            assertNotNull(reason);
            assertTrue(reason.startsWith("internal_error: "),
                    "reason 必须以 'internal_error: ' 开头；实际: " + reason);
            assertEquals("internal_error: ArrayIndexOutOfBoundsException", reason,
                    "R-83 字面量契约：reason = 'internal_error: <SimpleName>'");
        }
    }

    private static ServerRequest req(String path) {
        MockHttpServletRequest mock = new MockHttpServletRequest("GET", path);
        return ServerRequest.create(mock, java.util.List.of(
                new org.springframework.http.converter.StringHttpMessageConverter()));
    }

    /** 自定义异常类型用于验证 simpleName 透传 */
    static class CustomMtlException extends RuntimeException {
        CustomMtlException(String message) {
            super(message);
        }
    }
}