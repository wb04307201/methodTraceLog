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
        // 正则表达式：匹配以 @ 开头的注解块（可能跨多行），直到遇到非注解的 public 方法定义
        // 使用 (?m) 多行模式，(?s) 单行模式（让 . 匹配换行符）
        // 匹配：任意数量的 @ 注解（可能跨行），后跟 public 方法定义（直到方法结束大括号）
        String regex = "(?s)(?:^\\s*@.*?\\s*?(?=^\\s*public))?(^\\s*public\\s+.*?\\{(?:[^{}]++|\\{(?:[^{}]++|\\{[^{}]*\\})*\\})*\\})";

        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(code);

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            result.append(matcher.group(1)).append("\n");
        }

        return result.toString().trim();
    }
}
