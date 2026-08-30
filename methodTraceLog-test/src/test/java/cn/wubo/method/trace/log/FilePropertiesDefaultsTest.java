package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FilePropertiesDefaultsTest {

    @Test
    void defaults_are_sane_for_unbounded_runs() {
        MethodTraceLogProperties.FileProperties p = new MethodTraceLogProperties.FileProperties();
        // 必须有非空默认值，否则 150GB 事件会复现
        Assertions.assertNotNull(p.getMaxFileSize());
        Assertions.assertNotNull(p.getTotalSizeCap());
        // 默认值不能太离谱：单文件 >= 1MB，total >= 100MB
        Assertions.assertTrue(parseBytes(p.getMaxFileSize()) >= 1024 * 1024);
        Assertions.assertTrue(parseBytes(p.getTotalSizeCap()) >= 100 * 1024 * 1024);
    }

    private long parseBytes(String s) {
        if (s == null) return 0L;
        String trimmed = s.trim().toUpperCase();
        long multiplier = 1L;
        if (trimmed.endsWith("KB")) {
            multiplier = 1024L;
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        } else if (trimmed.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        } else if (trimmed.endsWith("GB")) {
            multiplier = 1024L * 1024L * 1024L;
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        } else if (trimmed.endsWith("TB")) {
            multiplier = 1024L * 1024L * 1024L * 1024L;
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        } else if (trimmed.endsWith("B")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return (long) (Double.parseDouble(trimmed.trim()) * multiplier);
    }
}
