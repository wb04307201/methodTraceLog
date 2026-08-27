package cn.wubo.method.trace.log.file;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LogFileServiceSizeFormatTest {

    @Test
    void formats_sizes_human_readably() {
        Assertions.assertEquals("1 B", LogFileService.formatSize(1));
        Assertions.assertEquals("1.0 KB", LogFileService.formatSize(1024));
        Assertions.assertEquals("1.5 MB", LogFileService.formatSize((long) (1.5 * 1024 * 1024)));
        Assertions.assertEquals("150.5 GB", LogFileService.formatSize((long) (150.5 * 1024 * 1024 * 1024)));
    }
}
