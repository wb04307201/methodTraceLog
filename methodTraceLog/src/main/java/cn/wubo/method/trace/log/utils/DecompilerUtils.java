package cn.wubo.method.trace.log.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@UtilityClass
@Slf4j
public class DecompilerUtils {

    /**
     * 默认反编译超时（秒）。CFR 在病态输入下可能长时间运行，生产环境探测到这种情形应早报警。
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    /**
     * 类字节最大尺寸（50MB）。超出直接拒绝，避免病态类把 tmp 分区撑爆。
     */
    private static final long MAX_CLASS_BYTES = 50L * 1024 * 1024;

    private static final String TMP_PREFIX = "mtl-decomp-";
    private static final String TMP_SUFFIX = ".class";

    /**
     * 后台执行 CFR 的线程池。daemon 线程，不阻止 JVM 退出；cached pool 按需伸缩。
     */
    private static final ExecutorService DECOMPILE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mtl-decompiler");
        t.setDaemon(true);
        return t;
    });

    public String decompile(String className, String methodName) {
        return decompile(className, methodName, DEFAULT_TIMEOUT_SECONDS);
    }

    public String decompile(String className, String methodName, long timeoutSeconds) {
        // 1. 解析类（不触发 static init），用 context classloader 兼容 Spring Boot devtools 等场景
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Class not found: " + className, e);
        }

        // 2. 选取 classloader。优先用加载该类的 CL，null 时 fallback 到 system
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }

        // 3. 用 getResourceAsStream 读 class 字节。classloader 内部处理 file / jar / jar-in-jar / war / 自定义 CL。
        String classResource = clazz.getName().replace('.', '/') + ".class";
        Path tempClass = null;
        try {
            tempClass = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
            try (InputStream in = cl.getResourceAsStream(classResource)) {
                if (in == null) {
                    throw new IllegalArgumentException("Class resource not found: " + classResource);
                }
                // 流式拷贝并限制最大字节数
                long copied = copyWithLimit(in, tempClass, MAX_CLASS_BYTES);
                if (copied > MAX_CLASS_BYTES) {
                    throw new IllegalArgumentException(
                            "Class resource too large: " + copied + " bytes > " + MAX_CLASS_BYTES + " (class=" + className + ")");
                }
            }

            // 4. CFR 解析可能阻塞，用独立线程 + future timeout 保护
            final String classFilePath = tempClass.toString();
            Future<String> future = DECOMPILE_EXECUTOR.submit(() -> runCfr(classFilePath, methodName));
            try {
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException(
                        "Decompile timeout after " + timeoutSeconds + "s for " + className + "#" + methodName, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Decompile interrupted for " + className, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("Decompile failed for " + className, cause);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read class bytes: " + classResource, e);
        } finally {
            if (tempClass != null) {
                try {
                    Files.deleteIfExists(tempClass);
                } catch (IOException ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * 流式拷贝 InputStream 到目标 Path，超过 maxBytes 就停止并返回当前已复制字节数。
     * 防止 OOM 或 tmp 分区被写满。
     */
    private static long copyWithLimit(InputStream in, Path target, long maxBytes) throws IOException {
        try (var out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    out.write(buf, 0, n);
                    return total;
                }
                out.write(buf, 0, n);
            }
            return total;
        }
    }

    /**
     * 用 CFR API 模式驱动反编译。不修改 System.out，多线程安全。
     * <p>
     * `--ignoreinvalid true` 允许在 classpath 不完整（生产环境只喂单个 .class）时跳过缺失引用类。
     */
    private static String runCfr(String classFilePath, String methodName) {
        StringBuilder javaOutput = new StringBuilder();
        Map<String, String> options = new HashMap<>();
        options.put("--methodname", methodName);
        options.put("--silent", "true");
        options.put("--ignoreinvalid", "true");

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<OutputSinkFactory.SinkClass> getSupportedSinks(
                    OutputSinkFactory.SinkType sinkType,
                    Collection<OutputSinkFactory.SinkClass> available) {
                // 只声明用得到的 sink。EXCEPTION 用于记录 CFR 内部错误。
                return List.of(OutputSinkFactory.SinkClass.STRING, OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE);
            }

            @Override
            public <T> OutputSinkFactory.Sink<T> getSink(
                    OutputSinkFactory.SinkType sinkType,
                    OutputSinkFactory.SinkClass sinkClass) {
                if (sinkClass == OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE) {
                    return (OutputSinkFactory.Sink<T>) (OutputSinkFactory.Sink<String>) message ->
                            log.warn("CFR exception: {}", message);
                }
                // sinkClass == STRING
                return (OutputSinkFactory.Sink<T>) (OutputSinkFactory.Sink<String>) chunk -> {
                    if (sinkType == OutputSinkFactory.SinkType.JAVA) {
                        javaOutput.append(chunk).append('\n');
                    }
                };
            }
        };

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(sinkFactory)
                .build();
        driver.analyse(List.of(classFilePath));
        return javaOutput.toString();
    }

    /**
     * 去掉 Java 源码中的注解行，便于把代码片段喂给 LLM。
     * <p>
     * 处理两类：
     *  1. 带括号的多行注解（@Foo(arg)）
     *  2. 单行注解（@Bar）
     * 同时折叠多余空行和行首空白。
     */
    public String removeAnnotations(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        String regex1 = "(?s)@\\w+\\([^)]*?\\)";
        String regex2 = "(?m)^\\s*@\\w+\\s*$";

        code = code.replaceAll(regex1, "");
        code = code.replaceAll(regex2, "");
        code = code.replaceAll("(?m)^\\s*$[\\r\\n]+", "");
        code = code.replaceAll("(?m)^\\s+", "");

        return code.trim();
    }
}
