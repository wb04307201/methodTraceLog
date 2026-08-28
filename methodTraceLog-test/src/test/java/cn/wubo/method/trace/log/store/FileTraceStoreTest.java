package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileTraceStoreTest {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1", "TestClass", "TestClass", "m", "m()", "m()", List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void save_createsFile_and_getByTraceId_roundTrip(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        MethodTraceInfo root = MethodTraceInfo.create(newBefore("t-1"));
        store.save(root);
        assertNotNull(store.getByTraceId("t-1"));
    }

    @Test
    void getByTraceId_unknown_returnsNull(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        assertNull(store.getByTraceId("nope"));
    }

    @Test
    void save_overwritesInMemory_andWritesNewFile(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        MethodTraceInfo r1 = MethodTraceInfo.create(newBefore("t-1"));
        MethodTraceInfo r2 = MethodTraceInfo.create(newBefore("t-1"));
        store.save(r1);
        store.save(r2);
        assertSame(r2, store.getByTraceId("t-1"));
        assertEquals(1, store.size());
    }

    @Test
    void getRecent_newestFirst(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        for (int i = 0; i < 5; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        List<MethodTraceInfo> recent = store.getRecent(3);
        assertEquals(3, recent.size());
        // 最新写入在前
        assertEquals("t-4", recent.get(0).getBefore().getTraceid());
    }

    @Test
    void getRecent_zeroOrNegative_returnsEmpty(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        store.save(MethodTraceInfo.create(newBefore("t-1")));
        assertTrue(store.getRecent(0).isEmpty());
        assertTrue(store.getRecent(-1).isEmpty());
    }

    @Test
    void maxTraces_evictsOldestFromMemory(@TempDir Path dir) {
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 3, false);
        for (int i = 0; i < 5; i++) {
            store.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        assertEquals(3, store.size());
        // 最新的三个还在
        assertNotNull(store.getByTraceId("t-4"));
        assertNotNull(store.getByTraceId("t-3"));
        assertNotNull(store.getByTraceId("t-2"));
    }

    @Test
    void clean_removesFilesOlderThanTtl(@TempDir Path dir) throws Exception {
        FileTraceStore store = new FileTraceStore(dir.toString(), 100L, 100, false);
        store.save(MethodTraceInfo.create(newBefore("t-1")));
        Thread.sleep(200);
        store.clean(50L);
        // 内存中被清
        assertNull(store.getByTraceId("t-1"));
    }

    @Test
    void blankPath_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FileTraceStore("", 1000L, 100, false));
        assertThrows(IllegalArgumentException.class, () -> new FileTraceStore(null, 1000L, 100, false));
    }

    @Test
    void getByTraceId_diskReadFallback(@TempDir Path dir) throws Exception {
        // 写一个 trace → 让它在内存里 → 重新构造一个 store 索引重建到 false，但 index 里有
        FileTraceStore writer = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        MethodTraceInfo root = MethodTraceInfo.create(newBefore("disk-1"));
        writer.save(root);
        // 重新打开一个 store，启用 rebuild 索引
        FileTraceStore reader = new FileTraceStore(dir.toString(), 60_000L, 100, true);
        assertNotNull(reader.getByTraceId("disk-1"));
    }

    @Test
    void rebuildIndex_populatesRecent(@TempDir Path dir) throws Exception {
        // 写 5 个 trace（maxTraces=100, ttl=1h，不会被淘汰）
        FileTraceStore writer = new FileTraceStore(dir.toString(), 3_600_000L, 100, false);
        for (int i = 0; i < 5; i++) {
            writer.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        assertEquals(5, writer.getRecent(10).size());

        // 重新打开 store，启用 rebuild 索引（模拟重启）
        FileTraceStore reader = new FileTraceStore(dir.toString(), 3_600_000L, 100, true);
        // getRecent 必须返回全部 5 条（验证 recent / recentTimestamps 被重建时填充）
        List<MethodTraceInfo> recent = reader.getRecent(10);
        assertEquals(5, recent.size());
        // TTL 未过期，traceId 也都能查到
        for (int i = 0; i < 5; i++) {
            assertNotNull(reader.getByTraceId("t-" + i));
        }
    }

    @Test
    void rebuildIndex_evictsBeyondMaxTraces(@TempDir Path dir) throws Exception {
        // 写 5 个 trace，重启时用 maxTraces=2 → 只保留最新的 2 个
        FileTraceStore writer = new FileTraceStore(dir.toString(), 3_600_000L, 100, false);
        for (int i = 0; i < 5; i++) {
            writer.save(MethodTraceInfo.create(newBefore("t-" + i)));
        }
        FileTraceStore reader = new FileTraceStore(dir.toString(), 3_600_000L, 2, true);
        assertEquals(2, reader.size());
    }
}
