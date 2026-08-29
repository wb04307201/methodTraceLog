package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.security.MtlSessionService;
import cn.wubo.method.trace.log.alerting.AlertingService;
import cn.wubo.method.trace.log.analyze.SlowMethodAnalyzer;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import cn.wubo.method.trace.log.CallServiceStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * LogConfig /decompile 端点 fallback 行为回归测试（F-04）。
 * <p>
 * 修复前：{@code extractMethod} 返回 {@code Optional.empty()} 时（典型场景：构造器
 * 名字 == 类名、没有"返回类型"）→ 路由抛 {@code ResponseStatusException(NOT_FOUND)}
 * → 客户端拿到 404 + "Method not found"。
 * <p>
 * 修复后：切不到时 fallback 到全量类源码（与 javadoc 承诺的"若无法切出则 fallback
 * 返回整类源码"对齐）。本测试验证对一个真实构造器调用，路由返回 200 + 非空 body
 * （即全量类源码），而不是 404。
 * <p>
 * 直接拿 ServerResponse 的 statusCode() + body() 读结果（不走 writeTo），
 * 简化测试、避免和 ServerResponse.Context 的内部实现耦合。
 */
class LogConfigDecompileEndpointFallbackTest {

    @Test
    void decompile_constructor_returns200WithFullClassSource_not404() throws Exception {
        String className = "cn.wubo.method.trace.log.ServiceCallInfo";
        String methodName = "ServiceCallInfo"; // 构造器：methodName == className

        ServerResponse resp = invokeDecompileRoute(className, methodName);

        // 关键断言：状态码必须是 200，body 必须非空（fallback 到全量类源码）
        int status = resp.statusCode().value();
        Object body = extractEntity(resp);
        Assertions.assertEquals(200, status,
                "decompile 构造器应返回 200（fallback 到全量源码），实际: " + status
                        + " body=" + (body == null ? "null" : body.toString().substring(0, Math.min(300, body.toString().length()))));
        Assertions.assertNotNull(body, "body 不能为 null");
        String bodyStr = body.toString();
        Assertions.assertFalse(bodyStr.isEmpty(), "body 必须非空（fallback 到全量类源码）");
        // body 应包含类源码标志
        Assertions.assertTrue(bodyStr.contains("ServiceCallInfo"),
                "fallback body 应包含类源码（包含 'ServiceCallInfo'）；实际 head: "
                        + bodyStr.substring(0, Math.min(300, bodyStr.length())));
    }

    @Test
    void decompile_realMethod_stillReturnsOnlyMethod() throws Exception {
        // 反向：正常方法（有返回类型）走 extractMethod 路径，body 应只含目标方法而不含
        // 其他方法。修复 F-04 应当不破坏已有 extractMethod 路径。
        String className = "cn.wubo.method.trace.log.ServiceCallInfo";
        String methodName = "copyOf";

        ServerResponse resp = invokeDecompileRoute(className, methodName);

        int status = resp.statusCode().value();
        Object body = extractEntity(resp);
        Assertions.assertEquals(200, status,
                "decompile 普通方法应 200；body: " + (body == null ? "null" : body.toString().substring(0, Math.min(300, body.toString().length()))));
        String bodyStr = body.toString();
        Assertions.assertTrue(bodyStr.contains("copyOf("), "body 应包含 copyOf 签名");
        // 抽出"目标方法"路径应比全量短很多（CFR 全量类源码通常 8k~20k 字符）
        Assertions.assertTrue(bodyStr.length() < 4000,
                "extractMethod 路径应只含目标方法（短），不应 fallback 到全量；body length=" + bodyStr.length());
    }

    /**
     * ServerResponse 接口本身没有 body() 方法，body 暴露在 EntityResponse 子接口上。
     * 真实返回类型是 DefaultEntityResponseBuilder 之类，这里用 EntityResponse 转换。
     */
    private static Object extractEntity(ServerResponse resp) {
        if (resp instanceof EntityResponse<?> er) {
            return er.entity();
        }
        // 兜底：未知的 ServerResponse 实现 —— 抛 IAE 让测试 fail-fast
        throw new IllegalStateException("expected EntityResponse, got " + resp.getClass());
    }

    private static ServerResponse invokeDecompileRoute(String className, String methodName) throws Exception {
        LogConfig cfg = new LogConfig();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MtlSessionService session = new MtlSessionService(60_000L);
        MethodTraceLogProperties props = new MethodTraceLogProperties();
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(), props);
        SimpleMonitorServiceImpl monitor = new SimpleMonitorServiceImpl(meterRegistry,
                new cn.wubo.method.trace.log.store.InMemoryTraceStore(), 8L * 60 * 60 * 1000L);
        SlowMethodAnalyzer analyzer = new SlowMethodAnalyzer(meterRegistry);
        Optional<AlertingService> alerting = Optional.empty();

        Method m = LogConfig.class.getDeclaredMethod("methodTraceLogRouter",
                CallServiceStrategy.class, SimpleMonitorServiceImpl.class,
                MethodTraceLogProperties.class, MtlSessionService.class,
                Optional.class, SlowMethodAnalyzer.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> router = (RouterFunction<ServerResponse>) m.invoke(
                cfg, strategy, monitor, props, session, alerting, analyzer);

        MockHttpServletRequest httpReq = new MockHttpServletRequest("GET", "/methodTraceLog/decompile");
        // MockHttpServletRequest.setQueryString 不会自动 parse 参数 —— 必须显式 setParameter
        httpReq.setParameter("className", className);
        httpReq.setParameter("methodName", methodName);
        ServerRequest req = ServerRequest.create(httpReq, List.of(new StringHttpMessageConverter()));

        // router.route() 返回 Optional<HandlerFunction<ServerResponse>>
        Optional<HandlerFunction<ServerResponse>> handlerOpt = router.route(req);
        Assertions.assertTrue(handlerOpt.isPresent(), "router 必须匹配到 /methodTraceLog/decompile");
        HandlerFunction<ServerResponse> handler = handlerOpt.get();
        return handler.handle(req);
    }
}
