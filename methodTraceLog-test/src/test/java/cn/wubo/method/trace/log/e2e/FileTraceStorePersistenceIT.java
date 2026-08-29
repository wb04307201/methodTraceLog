package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 FileTraceStore 的持久化能力：
 *  1. 启动 host A with file store，记录 trace，关闭。
 *  2. 启动 host B with same file path。
 *  3. 验证 host B 能从文件加载历史 trace（rebuildIndex on start）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileTraceStorePersistenceIT {

    private static final String STORE_PATH = "build/file-store-persistence-test";

    @BeforeAll
    static void cleanupStoreDir() throws IOException {
        Path dir = Paths.get(STORE_PATH);
        if (Files.exists(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Typed fetcher for {@code /methodTraceLog/view/list} — 直接
     * {@code List.class} 会被 Jackson 解成 {@code List<LinkedHashMap>}，
     * 到 {@code .getBefore()} 时抛 ClassCastException。这里走
     * {@link ParameterizedTypeReference} 落到 {@code List<MethodTraceInfo>}。
     */
    private List<MethodTraceInfo> fetchRoots(MtlE2eHarness host, int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = host.http().getRestTemplate().exchange(
                "http://localhost:" + host.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET, HttpEntity.EMPTY, typeRef);
        return resp.getBody();
    }

    @Test
    void file_store_persists_traces_across_restart() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.trace-store.type", "file");
        props.put("method-trace-log.log.trace-store.path", STORE_PATH);
        props.put("method-trace-log.log.trace-store.rebuild-index-on-start", "true");

        // Phase 1: record traces with first harness
        try (MtlE2eHarness hostA = MtlE2eHarness.primary(8099, props)) {
            hostA.http().getForEntity(
                    "http://localhost:8099/test/aspectLog?name=persist-test-A", String.class);
            // Wait for trace to be flushed
            List<MethodTraceInfo> roots = hostA.awaitTraceList(1, Duration.ofSeconds(5));
            assertThat(roots).isNotEmpty();
            // 等落盘一点缓冲时间
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Phase 2: open second harness with same path
        try (MtlE2eHarness hostB = MtlE2eHarness.primary(8100, props)) {
            // Fetch /view/list — should include traces from BOTH harnesses if rebuildIndex worked
            List<MethodTraceInfo> roots = fetchRoots(hostB, 50);

            assertThat(roots).as("second harness should load traces from disk").isNotNull();
            // Verify the trace from hostA is present (loaded from disk)
            boolean foundPersistTest = false;
            if (roots != null) {
                for (var r : roots) {
                    if (r.getBefore() != null
                            && r.getBefore().getMethodName().equals("aspectLog")
                            && r.getBefore().getContext() != null
                            && r.getBefore().getContext().toString().contains("persist-test-A")) {
                        foundPersistTest = true;
                        break;
                    }
                }
            }
            assertThat(foundPersistTest)
                    .as("trace from hostA (persist-test-A) should be loaded into hostB's store via rebuildIndex")
                    .isTrue();
        }
    }
}
