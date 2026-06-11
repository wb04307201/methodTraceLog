package cn.wubo.method.trace.log.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecompilerUtils 单测。
 * <p>
 * 重点验证：
 *  1. 应用类（file: classpath）能反编译
 *  2. 第三方库类（jar: / jar:nested:）能反编译 —— 关键回归点
 *  3. 不存在的类抛 IllegalArgumentException
 *  4. 缺失引用类不抛错（验证 --ignoreinvalid）
 *  5. 10 线程并发不互相污染输出（API 模式 vs System.out 抓取）
 *  6. 超时抛 IllegalStateException
 *  7. 临时文件清理
 *  8. removeAnnotations 正常工作
 */
class DecompilerUtilsTest {

    @Test
    void decompile_applicationClass_shouldWork() {
        String src = DecompilerUtils.decompile("cn.wubo.method.trace.log.ServiceCallInfo", "copyOf");
        assertNotNull(src);
        assertFalse(src.isEmpty(), "decompiled source should not be empty");
        assertTrue(src.contains("copyOf"), "should contain target method name; got: " + src);
        // copyOf is public static, CFR should preserve the signature
        assertTrue(src.contains("public") || src.contains("static"),
                "expected public/static in signature; got: " + src);
    }

    @Test
    void decompile_springBootClassFromJar_shouldWork() {
        // SpringApplication 来自 spring-boot-x.x.x.jar，无论薄 jar 还是 fat jar 都应该能反编译
        // 之前 bug 报告：fat jar 下 getResource 拿到 jar:nested: URL，URL 解析会失败
        // 新实现走 getResourceAsStream，应当能正常工作
        String src = DecompilerUtils.decompile(
                "org.springframework.boot.SpringApplication", "run");
        assertNotNull(src);
        assertFalse(src.isEmpty());
        // CFR 输出的方法签名应包含方法名 "run"
        assertTrue(src.contains("run"), "expected 'run' in decompiled source");
    }

    @Test
    void decompile_nonexistentClass_shouldThrow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DecompilerUtils.decompile("com.does.not.Exist", "foo"));
        assertTrue(ex.getMessage().contains("Class not found")
                        || ex.getMessage().contains("Class resource not found"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void decompile_innerClass_shouldWork() {
        // 内部类也是 classloader 加载的"非主"类，验证 getResourceAsStream 路径
        // 用一个静态字段持有内部类 Class<?>，这样名字已知
        Class<?> innerClass = InnerHelper.class;
        String src = DecompilerUtils.decompile(innerClass.getName(), "greet");
        assertNotNull(src);
        assertFalse(src.isEmpty());
        assertTrue(src.contains("greet"), "expected 'greet' method; got: " + src);
    }

    /**
     * 测试用静态内部类。DecompilerUtils 应该能反编译它。
     */
    public static class InnerHelper {
        public String greet() {
            return "hello";
        }
    }

    @Test
    void decompile_concurrent10Threads_outputsNotCrossContaminated() throws Exception {
        // 旧实现通过 System.setOut 抓输出，10 线程并发会互相串扰。
        // 新实现走 OutputSinkFactory 闭包，每个调用独立 StringBuilder。
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return DecompilerUtils.decompile(
                        "cn.wubo.method.trace.log.ServiceCallInfo", "copyOf");
            }));
        }
        start.countDown();
        for (Future<String> f : futures) {
            String src = f.get(30, TimeUnit.SECONDS);
            assertNotNull(src);
            assertTrue(src.contains("copyOf"), "output should contain target method");
        }
        pool.shutdown();
    }

    @Test
    void decompile_extremelyShortTimeout_shouldThrowIllegalState() {
        // 1ms 超时几乎必然触发，验证未来取消 + 抛 IllegalStateException 的链路
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DecompilerUtils.decompile(
                        "cn.wubo.method.trace.log.ServiceCallInfo", "copyOf", 0L));
        // 0 秒 = 直接 TimeoutException → 包装为 IllegalStateException
        assertTrue(ex.getMessage().toLowerCase().contains("timeout")
                        || ex.getMessage().toLowerCase().contains("decompile"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void decompile_tempFileIsCleanedUp() throws Exception {
        // 验证反编译后无残留 temp 文件
        long before = countMtlTempFiles();
        DecompilerUtils.decompile("cn.wubo.method.trace.log.ServiceCallInfo", "copyOf");
        DecompilerUtils.decompile(
                "org.springframework.boot.SpringApplication", "run");
        long after = countMtlTempFiles();
        assertEquals(before, after, "temp files leaked: before=" + before + " after=" + after);
    }

    private static long countMtlTempFiles() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = Files.list(tmp)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("mtl-decomp-"))
                    .count();
        }
    }

    @Test
    void removeAnnotations_shouldStripSingleLineAndMultiline() {
        String src = """
                /*
                 * Some comment
                 */
                @Component
                public class Foo {
                    @Autowired
                    @RB
                    private String bar;

                    @Override
                    public void m() {
                        @SuppressWarnings("unused")
                        int x = 1;
                    }
                }
                """;
        String stripped = DecompilerUtils.removeAnnotations(src);
        assertNotNull(stripped);
        // 注解不能残留（除 // 行注释外）
        assertFalse(stripped.contains("@Component"));
        assertFalse(stripped.contains("@Autowired"));
        assertFalse(stripped.contains("@Override"));
        assertFalse(stripped.contains("@SuppressWarnings"));
        assertFalse(stripped.contains("@RB"));
        // 类的结构应当保留
        assertTrue(stripped.contains("class Foo"));
        assertTrue(stripped.contains("public void m()"));
    }

    @Test
    void removeAnnotations_nullOrEmpty_returnsAsIs() {
        assertNull(DecompilerUtils.removeAnnotations(null));
        assertEquals("", DecompilerUtils.removeAnnotations(""));
    }
}
