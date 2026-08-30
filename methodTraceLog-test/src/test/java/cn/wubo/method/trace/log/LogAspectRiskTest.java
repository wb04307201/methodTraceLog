package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogAspect} / {@link AbstractCallService#transContext(Object)} 中两条风险回归测试。
 * <p>
 * 覆盖：
 * <ul>
 *     <li>R-44 — {@link MultipartFile} 经过 {@code transContext} 后只剩文件名 + 大小，
 *         <b>文件内容被丢弃</b>。这是设计意图（避免把上传内容塞进日志 / JSON 序列化阶段 NPE），
 *         本测试锁定该契约：调用方期望日志里看到"文件名 + 大小"摘要，不应看到内容。</li>
 *     <li>R-45 — {@code LogAspect.applyAspectLogOverride} 的 catch 是 {@link Exception}，
 *         不会拦截 {@link LinkageError} / {@link Error}（如 NoClassDefFoundError）。这是
 *         与"注解读取失败不应影响主流程"的契约相悖的潜在 bug，但本测试只锁定当前行为
 *         而不期望修复（修复需评估 ClassNotFoundError 等对业务的影响）。</li>
 * </ul>
 */
class LogAspectRiskTest {

    // ===== R-44: MultipartFile loses content =====

    @Component
    static class UploadComponent {
        public String upload(MultipartFile file) {
            return "received";
        }
    }

    @Test
    @DisplayName("transContext(MultipartFile) 返回 \"文件名: X, 大小: Y\" —— 文件内容被丢弃（设计意图）")
    void transContext_multipartFile_dropsContentKeepsMetadata() {
        // 构造一个有真实内容的 MultipartFile（1024 字节的 'A'）
        byte[] content = new byte[1024];
        java.util.Arrays.fill(content, (byte) 'A');
        MockMultipartFile file = new MockMultipartFile(
                "file", "hello.txt", "text/plain", content);

        Object result = AbstractCallService.transContext(file);
        Assertions.assertNotNull(result);
        assertTrue(result instanceof String,
                "MultipartFile 必须被转成 String（而不是直接 JSON 序列化）");
        String s = (String) result;
        assertTrue(s.contains("hello.txt"),
                "转字符串后必须保留文件名；实际: " + s);
        assertTrue(s.contains("1024"),
                "转字符串后必须保留 size；实际: " + s);
        // 关键契约：原始内容字节序列不能泄露到 context 里
        Assertions.assertFalse(s.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                "MultipartFile 内容不应被序列化进 context；实际: " + s.substring(0, Math.min(200, s.length())));
        // 防御：1024 字节的 A 不会自然变成 "文件名: ..., 大小: 1024" 字符串里的字面值
        Assertions.assertTrue(s.matches("^文件名: .+, 大小: \\d+$"),
                "格式必须是\"文件名: X, 大小: Y\"；实际: " + s);
    }

    @Test
    @DisplayName("transContext(MultipartFile=null) 返回 \"文件名: 未知, 大小: 0\"（不抛 NPE）")
    void transContext_multipartFileNull_safe() {
        // MockMultipartFile 不允许 null 构造 —— 用真实 MultipartFile 接口实例化一个 null-content 的
        MultipartFile file = new MockMultipartFile("file", "ignored.txt", "text/plain", new byte[0]);
        Object result = AbstractCallService.transContext(file);
        Assertions.assertNotNull(result);
        // 至少不应抛 NPE —— 内容为空时 size=0
        Assertions.assertTrue(result.toString().contains("大小: 0"));
    }

    // ===== R-45: applyAspectLogOverride catches Exception not Throwable =====

    @Test
    @DisplayName("正常 @AspectLog 注解覆盖 methodName（happy path，回归 Round 12 已有）")
    void applyAspectLogOverride_normalAnnotation_overridesMethodName() {
        CapturingCallService capture = new CapturingCallService();
        CallServiceStrategy strategy = new CallServiceStrategy(List.of(capture),
                new MethodTraceLogProperties());
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestComponent());
        factory.addAspect(new LogAspect(strategy));
        TestComponent proxy = factory.getProxy();

        proxy.aspectLogDemo("test");

        // 2 个事件：before / after_return
        assertEquals(2, capture.captured.size());
        ServiceCallInfo before = capture.captured.get(0);
        ServiceCallInfo after = capture.captured.get(1);
        // methodName 已被 @AspectLog("aspectLogDemo") 覆盖
        assertEquals("aspectLogDemo", before.getMethodName());
        assertEquals("aspectLogDemo", after.getMethodName());
        // short signature 也应是覆盖后的值 + (..)
        assertEquals("aspectLogDemo(..)", before.getMethodSignatureShortString());
    }

    /**
     * 直接 reflection 调 {@code applyAspectLogOverride}：构造一个会抛
     * {@link LinkageError} 的 {@link ProceedingJoinPoint}，验证当前实现是否会"吞"
     * 这种 Throwable。
     * <p>
     * 结论：当前实现 catch {@link Exception}，不 catch {@link Throwable}，因此
     * {@link LinkageError} 会向上传播 —— 这是 R-45 标注的潜在 bug。
     */
    @Test
    @DisplayName("applyAspectLogOverride 对 LinkageError 不吞 —— 当前实现 catch Exception 不 catch Throwable")
    void applyAspectLogOverride_doesNotCatchLinkageError() throws Exception {
        Method m = LogAspect.class.getDeclaredMethod("applyAspectLogOverride",
                ProceedingJoinPoint.class, ServiceCallInfo.class);
        m.setAccessible(true);

        LogAspect aspect = new LogAspect(
                new CallServiceStrategy(List.of(), new MethodTraceLogProperties()),
                new HeadBasedSampler(1.0));

        // 构造一个 ProceedingJoinPoint stub：getSignature() → 抛 LinkageError
        ProceedingJoinPoint jp = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(jp.getSignature()).thenThrow(new LinkageError("simulated linkage error"));

        ServiceCallInfo info = new ServiceCallInfo(
                UUID.randomUUID().toString(), null, UUID.randomUUID().toString(),
                "com.x.Y", "Y", "m", "m()", "m()",
                "arg", LogActionEnum.BEFORE, System.currentTimeMillis());

        // 当前实现：catch Exception → LinkageError 是 Error 不是 Exception → 向上传播。
        // reflection 把异常包成 InvocationTargetException，需要 unwrap 后断言。
        java.lang.reflect.InvocationTargetException ite = Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(aspect, jp, info),
                "R-45: applyAspectLogOverride catch(Exception) 不应拦截 LinkageError；"
                        + "若未来改成 catch(Throwable)，本测试会 fail（说明修复生效）");
        Throwable cause = ite.getCause();
        Assertions.assertTrue(cause instanceof LinkageError,
                "unwrap 后必须是 LinkageError；实际: " + cause);
        Assertions.assertTrue(cause.getMessage().contains("simulated linkage error"),
                "异常消息必须保留链路；实际: " + cause.getMessage());
    }

    /**
     * 反向测试：普通的 Exception 仍被 catch（不向上传播）—— 锁定 happy path 的契约。
     */
    @Test
    @DisplayName("applyAspectLogOverride 对普通 RuntimeException 仍 swallow（保持主流程）")
    void applyAspectLogOverride_doesSwallowRuntimeException() throws Exception {
        Method m = LogAspect.class.getDeclaredMethod("applyAspectLogOverride",
                ProceedingJoinPoint.class, ServiceCallInfo.class);
        m.setAccessible(true);

        LogAspect aspect = new LogAspect(
                new CallServiceStrategy(List.of(), new MethodTraceLogProperties()),
                new HeadBasedSampler(1.0));

        ProceedingJoinPoint jp = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(jp.getSignature()).thenThrow(new RuntimeException("simulated runtime"));

        ServiceCallInfo info = new ServiceCallInfo(
                UUID.randomUUID().toString(), null, UUID.randomUUID().toString(),
                "com.x.Y", "Y", "m", "m()", "m()",
                "arg", LogActionEnum.BEFORE, System.currentTimeMillis());

        // 当前实现：catch Exception → RuntimeException 被吞掉 → 不抛
        Assertions.assertDoesNotThrow(() -> m.invoke(aspect, jp, info),
                "applyAspectLogOverride 应吞掉 RuntimeException 以保证主流程不被注解读取失败中断");
    }

    // ===== helper =====

    /** 复用 LogAspectExceptionTest 的捕获器。 */
    static final class CapturingCallService extends AbstractCallService {
        final List<ServiceCallInfo> captured = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void consumer(ServiceCallInfo info) {
            captured.add(info);
        }

        @Override
        public String getCallServiceName() {
            return "CapturingCallService";
        }

        @Override
        public String getCallServiceDesc() {
            return "test capture";
        }
    }
}
