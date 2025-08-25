package cn.wubo.method.trace.log.utils;

public class FileUtils {

    private FileUtils() {
    }

    /**
     * 对文件名进行安全检查，防止路径遍历攻击和非法文件名
     * @param fileName 待检查的文件名字符串
     * @throws IllegalArgumentException 当文件名为空、过长、包含非法字符或路径遍历序列时抛出
     */
    public static void pathInspection(String fileName) {
        // 检查输入是否为null或空
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        // 检查文件名长度限制（常见文件系统限制）
        if (fileName.length() > 255) {
            throw new IllegalArgumentException("Filename too long");
        }

        // 使用正则表达式白名单方式检查文件名安全性
        // 只允许字母、数字、点、下划线、连字符
        if (!fileName.matches("^[a-zA-Z0-9._-]+$")) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // 检查是否包含路径遍历序列
        if (fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid filename");
        }
    }

}

