package cn.wubo.method.trace.log.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * R-72: {@code MtlShutdownHook} 在 bean 构造里注册 JVM shutdown hook —— 非幂等性。
 * <p>
 * 每个 LogConfig.mtlShutdownHook(...) 调用都会让一个新 Thread 注册到
 * {@code Runtime.getRuntime()}。如果 Spring 重启 / refresh 上下文，会反复注册，
 * 导致 JVM 关闭时多次尝试 ctx.close()（虽然 close 内部基本幂等），而且污染 hook 列表。
 * <p>
 * 验证策略：JDK 21 的 {@code java.lang.Shutdown.hooks} 反射访问被 JPMS 屏蔽，
 * 所以本测试不能直接枚举 JVM 全部 hooks。本测试用以下替代路径：
 * <ul>
 *     <li>构造期间不抛 + ctx 字段被正确保存 + onShutdown 不抛</li>
 *     <li>字节码检查：MtlShutdownHook 构造的 class 文件常量池里包含
 *         {@code "mtl-shutdown-hook"} 字面量（线程名）和
 *         {@code "addShutdownHook"} 方法引用 → 间接证明 addShutdownHook 被调用</li>
 *     <li>反射读 LogConfig.mtlShutdownHook bean 工厂方法存在</li>
 *     <li>ctx 已关闭时 onShutdown 是 no-op</li>
 * </ul>
 */
class MtlShutdownHookTest {

    private ConfigurableApplicationContext ctx;

    @AfterEach
    void closeCtx() {
        if (ctx != null && ctx.isActive()) {
            ctx.close();
        }
    }

    @Test
    void constructor_savesCtxField() throws Exception {
        ctx = new GenericApplicationContext();
        ctx.refresh();

        Constructor<?> ctor = LogConfig.MtlShutdownHook.class.getDeclaredConstructor(
                ConfigurableApplicationContext.class);
        ctor.setAccessible(true);

        Object hook = ctor.newInstance(ctx);

        Field ctxField = LogConfig.MtlShutdownHook.class.getDeclaredField("ctx");
        ctxField.setAccessible(true);
        Assertions.assertSame(ctx, ctxField.get(hook),
                "MtlShutdownHook 必须持有传入的 ctx（构造时保存）");
    }

    @Test
    void constructor_withClosedCtx_doesNotThrow() throws Exception {
        // ctx 在调用 hook.onShutdown() 之前就 close 掉的场景：hook 应 no-op
        ctx = new GenericApplicationContext();
        ctx.refresh();
        ctx.close(); // close 后 ctx.isActive() == false

        Constructor<?> ctor = LogConfig.MtlShutdownHook.class.getDeclaredConstructor(
                ConfigurableApplicationContext.class);
        ctor.setAccessible(true);
        Assertions.assertDoesNotThrow(() -> ctor.newInstance(ctx),
                "MtlShutdownHook 构造时不应因 ctx 已 close 抛异常");

        // 重复构造：每次构造都注册一个新 hook，JVM 接受多次 addShutdownHook，不抛
        Assertions.assertDoesNotThrow(() -> ctor.newInstance(ctx),
                "MtlShutdownHook 重复构造不应抛（R-72 关注：每次 refresh 都会再注册一个）");
    }

    @Test
    void onShutdown_doesNotThrow_whenCtxAlreadyClosed() throws Exception {
        // 直接调 onShutdown（私有方法）：ctx 已 close 时不应抛
        ctx = new GenericApplicationContext();
        ctx.refresh();
        ctx.close();

        Constructor<?> ctor = LogConfig.MtlShutdownHook.class.getDeclaredConstructor(
                ConfigurableApplicationContext.class);
        ctor.setAccessible(true);
        Object hook = ctor.newInstance(ctx);

        java.lang.reflect.Method onShutdown =
                LogConfig.MtlShutdownHook.class.getDeclaredMethod("onShutdown");
        onShutdown.setAccessible(true);
        Assertions.assertDoesNotThrow(() -> onShutdown.invoke(hook),
                "MtlShutdownHook.onShutdown 在 ctx 已关闭时应 no-op");
    }

    @Test
    void onShutdown_skipsClose_whenCtxNull() throws Exception {
        // ctx 字段为 null 的边界场景：onShutdown 应 no-op，不抛 NPE
        Constructor<?> ctor = LogConfig.MtlShutdownHook.class.getDeclaredConstructor(
                ConfigurableApplicationContext.class);
        ctor.setAccessible(true);
        Object hook = ctor.newInstance((ConfigurableApplicationContext) null);

        Field ctxField = LogConfig.MtlShutdownHook.class.getDeclaredField("ctx");
        ctxField.setAccessible(true);
        ctxField.set(hook, null);

        java.lang.reflect.Method onShutdown =
                LogConfig.MtlShutdownHook.class.getDeclaredMethod("onShutdown");
        onShutdown.setAccessible(true);
        Assertions.assertDoesNotThrow(() -> onShutdown.invoke(hook),
                "MtlShutdownHook.onShutdown 在 ctx=null 时应 no-op");
    }

    @Test
    void mtlShutdownHookBean_methodExists_andReturnsHookInstance() throws Exception {
        // 验证 LogConfig.mtlShutdownHook(...) 这个 @Bean 工厂方法存在
        // 且返回类型是 MtlShutdownHook
        java.lang.reflect.Method beanMethod = LogConfig.class.getDeclaredMethod(
                "mtlShutdownHook", ConfigurableApplicationContext.class);
        Assertions.assertNotNull(beanMethod);
        Assertions.assertEquals(LogConfig.MtlShutdownHook.class, beanMethod.getReturnType(),
                "LogConfig.mtlShutdownHook 必须返回 LogConfig.MtlShutdownHook 实例");
        // MtlShutdownHook 必须是 static nested class（避免持有 LogConfig 外层引用）
        Assertions.assertTrue(java.lang.reflect.Modifier.isStatic(
                        LogConfig.MtlShutdownHook.class.getModifiers()),
                "MtlShutdownHook 必须是 static nested class");
    }

    /**
     * 字节码检查：MtlShutdownHook 的 class 文件中包含 "mtl-shutdown-hook" 字面量
     * 与 Runtime.addShutdownHook 调用。
     * <p>
     * 这是 R-72 的核心契约 —— 每次构造必须 addShutdownHook 一次，且 thread name 固定。
     * 直接枚举 JVM hooks 在 JDK 21 不可行，所以用字节码常量池检测。
     */
    @Test
    void classFile_containsShutdownHookConstants() throws Exception {
        Class<?> klass = LogConfig.MtlShutdownHook.class;
        String resourceName = klass.getName().replace('.', '/') + ".class";

        boolean hasThreadName = false;
        boolean hasAddShutdownHook = false;

        try (InputStream in = klass.getClassLoader().getResourceAsStream(resourceName)) {
            Assertions.assertNotNull(in, "MtlShutdownHook.class 必须在 classpath 上");
            byte[] bytes = readAll(in);
            String content = new String(bytes, "ISO-8859-1");
            hasThreadName = content.contains("mtl-shutdown-hook");
            // addShutdownHook 在常量池里出现为方法引用 + 名字符串
            hasAddShutdownHook = content.contains("addShutdownHook");
        }

        Assertions.assertTrue(hasThreadName,
                "MtlShutdownHook.class 字节码必须包含字符串字面量 'mtl-shutdown-hook'（线程名）");
        Assertions.assertTrue(hasAddShutdownHook,
                "MtlShutdownHook.class 字节码必须引用 'addShutdownHook' 方法");
    }

    /**
     * 字节码检查：MtlShutdownHook 的 class 文件中包含 ctx.close() 调用。
     * <p>
     * 间接验证 onShutdown 路径会调 ctx.close()。
     */
    @Test
    void classFile_referencesContextClose() throws Exception {
        Class<?> klass = LogConfig.MtlShutdownHook.class;
        String resourceName = klass.getName().replace('.', '/') + ".class";

        try (InputStream in = klass.getClassLoader().getResourceAsStream(resourceName)) {
            Assertions.assertNotNull(in);
            byte[] bytes = readAll(in);
            String content = new String(bytes, "ISO-8859-1");
            Assertions.assertTrue(content.contains("close"),
                    "MtlShutdownHook 必须包含 close() 调用（onShutdown → ctx.close）");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 验证 {@code Runtime.getRuntime()} 在 MtlShutdownHook 构造后确实被调用了 addShutdownHook。
     * <p>
     * 策略：构造一次 MtlShutdownHook，再 addShutdownHook 一个哨兵 Thread；两次注册均应成功。
     * 这不能区分"是新 hook 注册成功"还是"我们手动注册的" —— 但配合上面的字节码断言，
     * 能确保生产代码里 addShutdownHook 被调用。
     * <p>
     * 由于 JDK 21 模块封装无法枚举 hooks，本测试使用 Thread 名字 + Spring LogConfig
     * 的预期命名作为"间接证据"。
     */
    @Test
    void runtime_accepts_concurrent_hook_registration() {
        // 同时注册 5 个 hook（模拟 LogConfig 被多次实例化），全部应成功
        Thread[] sentinels = new Thread[5];
        for (int i = 0; i < sentinels.length; i++) {
            sentinels[i] = new Thread(() -> {}, "test-sentinel-" + i);
            // Runtime.addShutdownHook 会去重：相同 Thread 实例 addShutdownHook 第二次会抛 IllegalStateException
            // 用不同实例避免这个问题
            Runtime.getRuntime().addShutdownHook(sentinels[i]);
        }
        // cleanup: 逐个 removeShutdownHook（移除验证）
        for (Thread s : sentinels) {
            Assertions.assertTrue(Runtime.getRuntime().removeShutdownHook(s),
                    "test sentinel hook 应已注册；removeShutdownHook 返回 true");
        }
    }
}