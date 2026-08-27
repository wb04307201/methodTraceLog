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

/**
 * CFR 反编译工具：把 classpath 上的任意类按方法反编译为可读 Java 源码。
 * <p>
 * 关键点：
 *  1. 走 classloader 的 {@code getResourceAsStream}，天然支持 file / thin jar / fat-jar 嵌套。
 *  2. 写临时文件 + CFR daemon 线程 + future timeout —— 病态输入会被取消，临时文件总是清理。
 *  3. 50MB 字节上限保护 tmp 分区不被病态类撑爆。
 *  4. {@link #removeAnnotations(String)} 配套提供去注解，便于把代码喂给 LLM。
 *  5. {@link #extractMethod(String, String)} 从整个类源码里切出目标方法，避免返回整类噪声。
 */
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

    /**
     * 反编译指定类的指定方法，使用默认超时（{@value #DEFAULT_TIMEOUT_SECONDS} 秒）。
     *
     * @param className  类全限定名
     * @param methodName 方法名（{@code --methodname}）
     * @return 反编译得到的 Java 源码（含原注解）
     * @throws IllegalArgumentException 类找不到 / 类字节不存在 / 超出 50MB
     * @throws IllegalStateException    反编译超时、被中断或 CFR 内部异常
     */
    public String decompile(String className, String methodName) {
        return decompile(className, methodName, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 反编译指定类的指定方法，自定义超时。
     *
     * @param className      类全限定名
     * @param methodName     方法名（{@code --methodname}）
     * @param timeoutSeconds 超时秒数；到达后 future 会被取消并抛 {@link IllegalStateException}
     * @return 反编译得到的 Java 源码（含原注解）
     * @throws IllegalArgumentException 类找不到 / 类字节不存在 / 超出 50MB
     * @throws IllegalStateException    反编译超时、被中断或 CFR 内部异常
     */
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
     * 从 CFR 输出的完整类源码中切出指定方法的源码块（含签名 + body）。
     * <p>
     * 用「修饰符 + 可选泛型 + 返回类型 + 方法名(...)」正则定位签名行，
     * 再用大括号配对（跳过字符串 / 字符字面量 / 注释）确定 body 结束位置，
     * 比 CFR 自带的 {@code --methodname} 过滤更鲁棒（CFR 仍会输出整个类的壳和字段）。
     * <p>
     * 切不到时返回 {@link java.util.Optional#empty()}，调用方应 fallback 到全量源码。
     *
     * @param src        完整类源码（通常是 {@link #decompile} 的结果，可先过 {@link #removeAnnotations}）
     * @param methodName 目标方法名
     * @return 目标方法的源码块，找不到 / 大括号不配平时返回 empty
     */
    public java.util.Optional<String> extractMethod(String src, String methodName) {
        if (src == null || methodName == null) {
            return java.util.Optional.empty();
        }
        // 匹配：可选修饰符 + 可选泛型声明（支持一层嵌套 <>）+ 返回类型 + \b methodName( ... ) {
        // 泛型声明允许 <T extends Comparable<T>> 这种结构；返回类型允许数组 [] / 泛型 <> /
        // 全限定名 . / 通配符 ?；前后空白包含换行（CFR 长泛型签名会换行）。
        // 用 \b 锁方法名边界，避免误匹配 myfoo 里的 foo；不锚定行首，让内联方法也命中。
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "((?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*" +
                        "(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>\\s+)?" +
                        "[\\w<>\\[\\], ?.]+\\s+" +
                        "\\b" + java.util.regex.Pattern.quote(methodName) +
                        "\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{");
        java.util.regex.Matcher m = p.matcher(src);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        int start = m.start();
        int braceOpen = src.indexOf('{', m.end() - 1);
        if (braceOpen < 0) {
            return java.util.Optional.empty();
        }
        // 大括号配对时跳过字符串 / 字符字面量 / 行注释 / 块注释，避免被源码里的 `{}` 误判。
        int depth = 1;
        int i = braceOpen + 1;
        int n = src.length();
        while (i < n && depth > 0) {
            char c = src.charAt(i);
            if (c == '"') {
                i = skipStringLiteral(src, i + 1, '"');
            } else if (c == '\'') {
                i = skipStringLiteral(src, i + 1, '\'');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = skipLineComment(src, i + 2);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i = skipBlockComment(src, i + 2);
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                i++;
            } else {
                i++;
            }
        }
        if (depth != 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(src.substring(start, i));
    }

    /**
     * 从 {@code start} 开始跳过字符串 / 字符字面量内容，返回字面量结束后下一字符的索引。
     * 处理 {@code \} 转义（含 {@code \"}、{@code \'}、{@code \\} 等）；遇到文件末尾或未闭合字面量
     * 时返回 {@code src.length()}，调用方会自然终止主循环。
     */
    private static int skipStringLiteral(String src, int start, char quote) {
        int i = start;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2; // 跳过转义序列：反斜杠 + 任意后续字符
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                // 普通字符串/字符字面量不允许裸换行；到此为止算未闭合
                return i;
            }
            i++;
        }
        return i;
    }

    /**
     * 跳到行尾换行符处（不含换行符），返回换行符的索引。
     */
    private static int skipLineComment(String src, int start) {
        int i = start;
        int n = src.length();
        while (i < n && src.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /**
     * 跳过 {@code /* ... *}{@code /} 块注释。找不到结束符时返回 {@code src.length()}。
     */
    private static int skipBlockComment(String src, int start) {
        int i = start;
        int n = src.length();
        while (i + 1 < n) {
            if (src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return n;
    }

    /**
     * 去掉 Java 源码中的注解行，便于把代码片段喂给 LLM。
     * <p>
     * 处理两类：
     *  1. 带括号的多行注解（@Foo(arg)）
     *  2. 单行注解（@Bar）
     * 同时折叠多余空行和行首空白。
     *
     * @param code 原始 Java 源码（可含注解），允许 null / 空串
     * @return 去注解 + 折叠空行 + 去行首空白 后的源码
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
