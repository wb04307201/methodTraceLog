package cn.wubo.method.trace.log.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路径白名单校验的安全测试。
 * <p>
 * {@link FileUtils#pathInspection(String)} 是 LogFileService / LogFileRealTimeService
 * 读盘前的唯一关卡。绕过它意味着攻击者可以通过 {@code ../etc/passwd} 之类
 * 的字符串读到日志目录以外的文件，因此这里的"合法"用例必须真的合法、
 * "非法"用例必须真的被拒。
 */
class FileUtilsTest {

    // ---------- 合法用例 ----------

    @Test
    void accepts_simple_filename() {
        assertDoesNotThrow(() -> FileUtils.pathInspection("myapp.log"));
    }

    @Test
    void accepts_dotted_filename() {
        assertDoesNotThrow(() -> FileUtils.pathInspection("my.app.log"));
    }

    @Test
    void accepts_dash_and_underscore() {
        assertDoesNotThrow(() -> FileUtils.pathInspection("my-app_v2.log"));
    }

    // ---------- 路径遍历 / 非法用例 ----------

    @Test
    void rejects_parent_traversal() {
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection("../etc/passwd"));
    }

    @Test
    void rejects_absolute_path() {
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection("/etc/passwd"));
    }

    @Test
    void rejects_windows_separator() {
        // 反斜杠不在白名单 [a-zA-Z0-9._-] 里，必须拒。
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection("..\\windows\\path"));
    }

    @Test
    void rejects_null() {
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection(null));
    }

    @Test
    void rejects_empty() {
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection(""));
    }

    @Test
    void rejects_too_long() {
        // 256 字符的文件名必然超出 255 上限。
        String tooLong = "a".repeat(256);
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection(tooLong));
    }

    @Test
    void rejects_special_chars() {
        // 分号 / 空格 / 连写 shell 元字符全部不在白名单。
        assertThrows(IllegalArgumentException.class,
                () -> FileUtils.pathInspection("file;rm -rf.log"));
    }
}