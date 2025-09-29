package cn.wubo.method.trace.log.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
@Slf4j
public class DecompilerUtils {

    public String decompile(String className, String methodName) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, ClassNotFoundException, UnsupportedEncodingException {
        // 获取类的 .class 文件路径
        Class<?> clazz = Class.forName(className);
        String classResource = clazz.getName().replace('.', '/') + ".class";
        URL classUrl = clazz.getClassLoader().getResource(classResource);
        if (classUrl == null) {
            throw new IllegalArgumentException("Class file not found: " + clazz.getName());
        }

        String classPath = classUrl.getPath();
        // 如果是 JAR 中的类，需要特殊处理（CFR 支持 jar:file://...）
        // 但为简化，假设是文件系统中的 class

        // 构造 CFR 参数
        String[] args = {
                classPath,
                "--methodname", methodName,
                "--silent", "true"
        };

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
