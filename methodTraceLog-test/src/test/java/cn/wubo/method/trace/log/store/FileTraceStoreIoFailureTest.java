package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FileTraceStore 在 IO 失败时的兜底行为测试。
 * <p>
 * 修复前：IOException 被吞掉，trace 丢失，仅打一条 WARN；recent / index 都不会留
 * 该 traceId 的记录，getByTraceId 返回 null。本次主要锁住：
 *  <ul>
 *      <li>save 不抛异常（吞掉 IOException）</li>
 *      <li>save 之后 size() 不会增长（recent 不留）</li>
 *      <li>getByTraceId 返回 null（index / recent 都没有该 traceId）</li>
 *  </ul>
 * <p>
 * 通过把根目录指向一个已存在的普通文件（不是目录）来制造真实的 IO 失败 —— 此时
 * Files.createDirectories(dayDir) 会抛 IOException。
 */
class FileTraceStoreIoFailureTest {

    private ServiceCallInfo newBefore(String traceid) {
        return new ServiceCallInfo(traceid, null, traceid + "-s1",
                "TestClass", "TestClass", "m", "m()", "m()",
                List.of(1), LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void save_whenSubdirCannotBeCreated_swallowsException(@TempDir Path dir) throws IOException {
        // 准备：rootPath 是 dir/file（普通文件，不能 createDirectories 在其下）
        Path blockingFile = dir.resolve("blocker");
        Files.writeString(blockingFile, "I am a regular file, not a directory");

        // FileTraceStore 构造会先 createDirectories(rootPath) —— 这里 rootPath 是普通文件，
        // 因此 Files.createDirectories 会抛 IOException。我们改用子目录作为 rootPath，
        // 然后把 day-dir 子路径替换为与 blockingFile 重名 —— 这要求拦截 dayDir 的解析。
        //
        // 简化方案：rootPath = dir/blocker 自身（普通文件）。FileTraceStore 构造时
        // Files.createDirectories(rootPath) 会失败 → IllegalStateException。
        //
        // 我们要测的是 save() 内部 try/catch 的行为，所以需要构造时 rootPath 合法，
        // 而 save() 时 Files.createDirectories(dayDir) 失败。把 dayDir 与一个已存在文件重名。
        Path rootPath = dir.resolve("store-root");
        Files.createDirectories(rootPath);

        FileTraceStore store = new FileTraceStore(rootPath.toString(), 60_000L, 100, false);

        // 在 store-root 下放一个与 dayDir 同名的文件
        // dayDir 由 LocalDate.now() 决定，无法预测。把整个 rootPath 替换为"指向一个
        // 已经被替换为文件的路径"——更稳的办法是直接让 rootPath 在 save 时被替换为文件。
        // 这里用一个本地重写的方式：让 rootPath 在 save 路径上变成普通文件。
        // 最干净的方法：把 rootPath 替换为 file（不是 directory）。
        //
        // 既然无法精确控制 dayDir，改方案：构造时 rootPath=合法 directory，
        // save 时把 rootPath 改成 file —— 仍然能制造 IO 失败，但需要反射改 store.rootPath。
        // 简化：用反射把 store.rootPath 改成一个与子目录同名的 blocker 文件。
        Path newDayDirCandidate = rootPath.resolve(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        // 把 rootPath 整体替换成"不能成为 directory 的文件"（删除 rootPath 目录并替换）
        // 删 rootPath 然后建同名文件
        deleteRecursively(rootPath);
        Files.writeString(rootPath, "blocker");

        // save 现在会 fail because Files.createDirectories(dayDir) → IOException
        MethodTraceInfo root = MethodTraceInfo.create(newBefore("t-fail"));
        Assertions.assertDoesNotThrow(() -> store.save(root));

        // 由于 IO 失败，trace 没有写入 store
        Assertions.assertEquals(0, store.size(),
                "IO 失败时 store.size() 必须为 0；got: " + store.size());
        Assertions.assertNull(store.getByTraceId("t-fail"));
        Assertions.assertTrue(store.getRecent(10).isEmpty());
    }

    @Test
    void save_normal_path_works(@TempDir Path dir) {
        // 反向对照：IO 正常时 save 正常工作
        FileTraceStore store = new FileTraceStore(dir.toString(), 60_000L, 100, false);
        store.save(MethodTraceInfo.create(newBefore("t-ok")));
        Assertions.assertNotNull(store.getByTraceId("t-ok"));
        Assertions.assertEquals(1, store.size());
    }

    @Test
    void blankPath_throws_illegalArgumentException() {
        // 文件路径校验失败的边界
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FileTraceStore("", 1000L, 100, false));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FileTraceStore("   ", 1000L, 100, false));
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                for (Path child : stream.toList()) deleteRecursively(child);
            }
        }
        Files.delete(p);
    }
}