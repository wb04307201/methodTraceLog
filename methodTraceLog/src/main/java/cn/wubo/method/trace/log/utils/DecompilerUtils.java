package cn.wubo.method.trace.log.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@UtilityClass
@Slf4j
public class DecompilerUtils {

    public String decompile(String className, String methodName) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, ClassNotFoundException, IOException {
        // 获取类的 .class 文件路径
        Class<?> clazz = Class.forName(className);
        String classResource = clazz.getName().replace('.', '/') + ".class";
        URL classUrl = clazz.getClassLoader().getResource(classResource);
        if (classUrl == null) {
            throw new IllegalArgumentException("Class file not found: " + clazz.getName());
        }

        String[] args;
        String classPath = classUrl.getPath();
        log.info("Class file path: {}", classPath);
        if ("jar".equals(classUrl.getProtocol())) {
            int jarEndIndex = classPath.indexOf(".jar/!");
            String jarFilePath = classPath.substring(0, jarEndIndex + 4).replace("nested:/", "");
            log.info("Jar file path: {}", jarFilePath);
            String innerClassPath = classPath.substring(jarEndIndex + 6).replace("/!/", "/"); // 去掉 "!/" 前缀
            log.info("Inner class path: {}", innerClassPath);

            Path tempDir = Files.createTempDirectory("decompiled");

            unzipJar(jarFilePath, tempDir.toFile());

            String classAbsolutePath = Paths.get(tempDir.toString(), innerClassPath).toString();
            args = new String[]{
                    classAbsolutePath,
                    "--methodname", methodName,
                    "--silent", "true"
            };
        } else {
            args = new String[]{
                    classPath,
                    "--methodname", methodName,
                    "--silent", "true"
            };

        }

        // 捕获 System.out 输出
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream newOut = new PrintStream(baos);
        System.setOut(newOut);

        try {
            // 调用 CFR Main
            Class<?> mainClass = Class.forName("org.benf.cfr.reader.Main");
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } finally {
            System.setOut(oldOut);
        }

        return baos.toString("UTF-8");
    }

    /**
     * 解压 JAR 文件到指定目录
     *
     * @param jarFilePath   JAR 文件路径
     * @param destDirectory 目标解压目录
     * @throws IOException IO 异常
     */
    public static void unzipJar(String jarFilePath, File destDirectory) throws IOException {
        if (!destDirectory.exists()) {
            destDirectory.mkdirs();
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File entryFile = new File(destDirectory, entry.getName());

                // 防止路径遍历漏洞
                if (!entryFile.toPath().normalize().startsWith(destDirectory.toPath().normalize())) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    // 确保父目录存在
                    entryFile.getParentFile().mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }


    public String removeAnnotations(String code) {
        // 正则表达式：匹配以 @ 开头的注解（可能跨多行）
        // (?m) 表示多行模式，^ 匹配每行开头
        // (?s) 表示单行模式，. 可以匹配换行符（用于跨行注解）
        // 我们先处理跨多行的注解：@XXX( ... )
        String regex1 = "(?s)@\\w+\\([^)]*?\\)"; // 匹配 @Name(...) 跨行
        String regex2 = "(?m)^\\s*@\\w+\\s*$";   // 匹配单行 @Name

        // 先移除带括号的多行注解（包括换行）
        code = code.replaceAll(regex1, "");

        // 再移除单行注解（如 @RB）
        code = code.replaceAll(regex2, "");

        // 移除多余的空行（可选）
        code = code.replaceAll("(?m)^\\s*$[\\r\\n]+", "");

        // 去掉行首多余的空白（可选，保持整洁）
        code = code.replaceAll("(?m)^\\s+", "");

        return code.trim();
    }
}
