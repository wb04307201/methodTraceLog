package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceStore 端到端测试 — 验证
 * {@code method-trace-log.log.trace-store.type} 的三种取值
 * （{@code in-memory} / {@code file} / {@code none}）在真实 Spring Boot
 * 启动 + AOP 拦截 + {@code /view/list} 这条管线上端到端生效。
 *
 * <p><b>关键路径：</b>
 * <ul>
 *   <li>{@code LogConfig.mtlTraceStore(MethodTraceLogProperties)} 根据
 *       {@code trace-store.type} 选择 {@link cn.wubo.method.trace.log.store.ITraceStore}
 *       实现：{@code in-memory} → {@code InMemoryTraceStore}；
 *       {@code file} → {@code FileTraceStore}（每个根 trace 序列化为
 *       {@code <path>/<yyyy-MM-dd>/trace-<traceid>-<timestamp>.json}，
 *       启动时可重建索引）；{@code none} → {@code NoOpTraceStore}
 *       （{@code save} / {@code getRecent} 都是 no-op / 空列表，
 *       但 Micrometer 指标照常写入）。</li>
 *   <li>{@code SimpleMonitorServiceImpl} 在 BEFORE 时
 *       {@code traceStore.save(root)}，AFTER 时再次
 *       {@code traceStore.save(root)} 让 store 看到 after 字段；
 *       {@code getMethodTraceInfos(...)} 直接从 {@code traceStore.getRecent(...)}
 *       拉根列表。</li>
 *   <li>端点 {@code GET /methodTraceLog/view/list} 返回
 *       {@code SimpleMonitorServiceImpl.getMethodTraceInfos(...)}
 *       的结果 — 子调用不会出现在根列表里。</li>
 * </ul>
 *
 * <p><b>关于根方法名 / 子方法名：</b>
 * {@code /test/aspectLog} 路由到 {@code TestController.aspectLog(name)}，
 * 内部调用 {@code TestComponent.aspectLogDemo(name)}（{@code @AspectLog("aspectLogDemo")}
 * 注解只重命名 inner 方法的显示名）。
 * 因此 {@code /view/list} 里根节点的 {@code before.methodName == "aspectLog"}
 * （{@code TestController} 上），而 {@code "aspectLogDemo"} 是它的子节点。
 * brief 的 {@code assertThat(list.stream().anyMatch(r -> "aspectLogDemo".equals(r.getMethodName())))}
 * 假设根就是 {@code aspectLogDemo} — 错的。需要走完整 trace 子树
 * （{@code MtlE2eHarness.findInTrace} 已经按 methodName 深度优先搜索）。</p>
 *
 * <p><b>断言要点（per parent-task notes）：</b>
 * <ol>
 *   <li>{@code MethodTraceInfo} 没有 {@code getMethodName()} / {@code getClassName()}
 *       （per Ruling 1），方法名/类名在 {@code getBefore().getMethodName()} /
 *       {@code getBefore().getClassName()}。</li>
 *   <li>直接 {@code List.class} 反序列化会被 Jackson 解成 {@code List<LinkedHashMap>}
 *       （per Ruling 4），必须走 {@link ParameterizedTypeReference} 落到真正的
 *       {@code List<MethodTraceInfo>}。harness 的 {@code awaitTraceList}
 *       用 raw {@code List.class}，有同样 bug — 这里写自己的 typed fetcher。</li>
 *   <li>brief 的 {@code none_store_records_no_traces} 用
 *       {@code r.getMethodName()} / {@code r.getClassName()} —
 *       这两个方法在 {@code MethodTraceInfo} 上不存在，会编译失败。
 *       必须走 {@code r.getBefore().getMethodName()} /
 *       {@code r.getBefore().getClassName()}，并把断言加强到：
 *       "根列表中不应出现任何 {@code TestComponent#aspectLogDemo} 节点"。
 *       因为每个测试用 try-with-resources 起独立的 Spring context，
 *       {@code none} store 的 {@code getRecent(limit)} 返回空 List.of()，
 *       整个根列表就是空的，断言更稳。</li>
 *   <li>{@code file} 测试在 {@code finally} 里清理
 *       {@code build/file-store-test/} 目录，避免污染仓库
 *       （{@code .gitignore} 已经忽略 {@code build/}）。</li>
 * </ol>
 *
 * <p><b>端口分配：</b>
 * <ul>
 *   <li>{@code 8085} — in-memory（默认配置）</li>
 *   <li>{@code 8093} — file</li>
 *   <li>{@code 8094} — none</li>
 * </ul>
 * 每个测试方法用 {@code try-with-resources} 起独立的 Spring context，
 * 不存在端口冲突（Ruling 5：每个 IT 在 surefire 的独立 JVM 中运行）。</p>
 */
class TraceStoreIT {

    private static final String TEST_COMPONENT_FQN = "cn.wubo.method.trace.log.TestComponent";
    private static final String ASPECT_LOG_DEMO = "aspectLogDemo";

    /**
     * Typed fetcher for {@code /methodTraceLog/view/list} — 直接
     * {@code List.class} 会被 Jackson 解成 {@code List<LinkedHashMap>}
     * （per Ruling 4），调用 {@code .getBefore()} 时抛
     * {@code NoSuchMethodError}。这里走 {@link ParameterizedTypeReference}
     * 直接落到 {@code List<MethodTraceInfo>}。
     */
    private List<MethodTraceInfo> fetchRoots(MtlE2eHarness host, int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = host.http().getRestTemplate().exchange(
                "http://localhost:" + host.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET, HttpEntity.EMPTY, typeRef);
        return resp.getBody();
    }

    /**
     * 轮询根 trace 列表，直到出现一个根节点，且其子树
     * （深度优先，包含根自身）中有 {@code TEST_COMPONENT_FQN#ASPECT_LOG_DEMO}
     * 节点；命中后返回该根节点。timeout 内未命中抛 AssertionError。
     *
     * <p>为什么必须 walk 子树：{@code /test/aspectLog} 的根是
     * {@code TestController.aspectLog}（{@code before.methodName == "aspectLog"}），
     * {@code TestComponent.aspectLogDemo} 是它的子调用。
     * brief 直接查根节点的 methodName 是错的；
     * 正确语义是"trace 中存在 TestComponent#aspectLogDemo 节点"。
     * 这里复用 {@link MtlE2eHarness#findInTrace}（它已经按
     * {@code before.methodName} 深度优先搜索）。</p>
     *
     * <p>在每个测试的 fresh harness 上，{@code /view/list} 起始为空，
     * 一旦 {@code /test/aspectLog} 调用到达并完成，{@code InMemoryTraceStore}
     * / {@code FileTraceStore} 的 {@code save(root)} 在
     * {@code SimpleMonitorServiceImpl.consumer(...)} 的 BEFORE + AFTER 阶段
     * 触发；200 ms 轮询间隔足够看到节点出现。</p>
     */
    private MethodTraceInfo awaitAspectLogDemoRoot(MtlE2eHarness host, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> roots = fetchRoots(host, 50);
                if (roots != null) {
                    for (var r : roots) {
                        if (r == null) continue;
                        Optional<MethodTraceInfo> hit = host.findInTrace(r, ASPECT_LOG_DEMO);
                        if (hit.isPresent()) return r;
                    }
                }
            } catch (Exception e) {
                lastError = new AssertionError(
                        "Error fetching trace list: " + e.getMessage(), e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(
                "No root trace in /view/list had a descendant with methodName="
                        + ASPECT_LOG_DEMO + " within " + timeout,
                lastError);
    }

    /**
     * 递归遍历 {@link MethodTraceInfo} 子树，收集
     * {@code before.className == targetClass && before.methodName == targetMethod}
     * 的所有节点（按访问顺序）。
     */
    private void collectNodesByClassAndMethod(
            MethodTraceInfo node, String targetClass, String targetMethod,
            List<MethodTraceInfo> sink) {
        if (node == null || node.getBefore() == null) return;
        String cn = node.getBefore().getClassName();
        String mn = node.getBefore().getMethodName();
        if (targetClass.equals(cn) && targetMethod.equals(mn)) {
            sink.add(node);
        }
        if (node.getChildren() != null) {
            for (var child : node.getChildren()) {
                collectNodesByClassAndMethod(child, targetClass, targetMethod, sink);
            }
        }
    }

    /**
     * 清理 file-store 的临时目录。{@code try-with-resources} 关闭 harness
     * 会触发 Spring context close → ITraceStore 不再被使用，但磁盘文件保留。
     * {@code build/} 已在 {@code .gitignore} 忽略；显式清理避免污染。
     */
    private void deleteFileStoreDir(String path) {
        Path root = Paths.get(path);
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) { /* best effort */ }
                    });
        } catch (IOException ignore) { /* best effort */ }
    }

    /**
     * in-memory（默认）— {@code traceStore.type=in-memory}：
     * 调用 {@code /test/aspectLog} 后，{@code /view/list} 应出现一个根节点，
     * 其 trace 子树中存在 {@code TestComponent#aspectLogDemo} 节点。
     *
     * <p>strengthen from brief：
     * <ul>
     *   <li>brief 断言
     *       {@code anyMatch(r -> "aspectLogDemo".equals(r.getMethodName()))} —
     *       但 {@code MethodTraceInfo} 没有 {@code getMethodName()}（per Ruling 1），
     *       会编译失败。</li>
     *   <li>brief 的方法名匹配还有更深的 bug：{@code aspectLogDemo} 不是根方法名
     *       — 它是 {@code TestController.aspectLog}（{@code /test/aspectLog} 的端点）
     *       的内部子调用。根的 {@code before.methodName} 是 {@code "aspectLog"}。
     *       必须 walk 子树去找 {@code TestComponent#aspectLogDemo}。</li>
     *   <li>精确匹配：{@code TEST_COMPONENT_FQN == cn.wubo.method.trace.log.TestComponent}
     *       且 methodName == {@code "aspectLogDemo"}，避免误中其它类上同名方法。</li>
     * </ul>
     *
     * <p>sample-rate 默认 1.0（{@code application.yml} 未设置，
     * {@code LogProperties.sampleRate} 默认 1.0），所有根调用都会被采样。</p>
     */
    @Test
    void in_memory_store_records_traces() {
        try (MtlE2eHarness host = MtlE2eHarness.primary(8085, Map.of())) {
            host.http().getForEntity(
                    "http://localhost:8085/test/aspectLog?name=store-inmem", String.class);

            MethodTraceInfo root = awaitAspectLogDemoRoot(host, Duration.ofSeconds(5));
            assertThat(root.getBefore())
                    .as("a root trace should appear in /view/list under in-memory store")
                    .isNotNull();
            // 根是 TestController#aspectLog（端点方法），子节点是 TestComponent#aspectLogDemo
            List<MethodTraceInfo> hits = new java.util.ArrayList<>();
            collectNodesByClassAndMethod(root, TEST_COMPONENT_FQN, ASPECT_LOG_DEMO, hits);
            assertThat(hits)
                    .as("trace subtree must contain exactly one TestComponent#aspectLogDemo node "
                            + "(/test/aspectLog calls testComponent.aspectLogDemo internally); "
                            + "root was: " + root.getBefore().getClassName() + "#"
                            + root.getBefore().getMethodName())
                    .hasSize(1);
        }
    }

    /**
     * file — {@code traceStore.type=file} 且
     * {@code traceStore.path=build/file-store-test}：
     * 调用 {@code /test/aspectLog} 后，{@code /view/list} 应出现一个根节点，
     * 其 trace 子树中存在 {@code TestComponent#aspectLogDemo} 节点，且
     * 磁盘目录里能看到至少一个 {@code trace-*.json} 文件。
     *
     * <p>{@code FileTraceStore} 启动时如果 {@code rebuildOnStart=true} 会扫描
     * 目录重建索引；这里保持默认 {@code false}（快速启动）。
     * 落盘发生在 {@code save(root)}（BEFORE + AFTER 都触发），最终根目录
     * 会形成 {@code <yyyy-MM-dd>/trace-<traceid>-<ms>.json}。</p>
     *
     * <p>strengthen from brief：brief 只断言
     * {@code assertThat(list).isNotEmpty()}。这里改为精确匹配
     * {@code TestComponent#aspectLogDemo}（同 in-memory 测试），
     * 并额外验证磁盘目录里出现了 JSON 文件。</p>
     */
    @Test
    void file_store_records_traces_and_rebuilds_index() {
        String storePath = "build/file-store-test";
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.trace-store.type", "file");
        props.put("method-trace-log.log.trace-store.path", storePath);
        try (MtlE2eHarness host = MtlE2eHarness.primary(8093, props)) {
            host.http().getForEntity(
                    "http://localhost:8093/test/aspectLog?name=store-file", String.class);

            MethodTraceInfo root = awaitAspectLogDemoRoot(host, Duration.ofSeconds(5));
            List<MethodTraceInfo> hits = new java.util.ArrayList<>();
            collectNodesByClassAndMethod(root, TEST_COMPONENT_FQN, ASPECT_LOG_DEMO, hits);
            assertThat(hits)
                    .as("trace subtree must contain exactly one TestComponent#aspectLogDemo node "
                            + "under file store; root was: " + root.getBefore().getClassName() + "#"
                            + root.getBefore().getMethodName())
                    .hasSize(1);

            // 额外：磁盘上至少能看到一个 trace-*.json（FileTraceStore.save 写入）。
            // 等落盘一点缓冲时间避免 race。
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            long jsonCount;
            try (var stream = Files.walk(Paths.get(storePath))) {
                jsonCount = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("trace-") && name.endsWith(".json");
                        })
                        .count();
            } catch (IOException ioe) {
                throw new AssertionError("Failed to walk file-store dir " + storePath, ioe);
            }
            assertThat(jsonCount)
                    .as("FileTraceStore should write at least one trace-<traceid>-<ts>.json under "
                            + storePath + "/<yyyy-MM-dd>/ for the captured TestController#aspectLog call")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            // 不管测试结果如何都清理临时目录，避免污染仓库
            deleteFileStoreDir(storePath);
        }
    }

    /**
     * none — {@code traceStore.type=none}：
     * 调用 {@code /test/aspectLog} 后，{@code /view/list} 不应出现任何
     * {@code TestComponent#aspectLogDemo} 节点；理想情况整个根列表为空。
     *
     * <p>{@code NoOpTraceStore.save(...)} 是 no-op，{@code getRecent(limit)}
     * 返回 {@code List.of()}；{@code SimpleMonitorServiceImpl.getMethodTraceInfos(...)}
     * 因此返回空 list。Micrometer 指标仍然写入（{@code SimpleMonitorServiceImpl}
     * 在 {@code save} 之外独立维护 {@code Timer.Sample}），
     * 这一点 {@code SimpleMethodStats} / {@code /actuator/methodtrace} 能
     * 验证 — 不在本测试范围。</p>
     *
     * <p>strengthen from brief：brief 的断言是
     * {@code arr == null || arr.stream().noneMatch(r -> "aspectLogDemo".equals(r.getMethodName()) && r.getClassName().contains("TestComponent"))} —
     * 两个问题：
     * <ol>
     *   <li>{@code MethodTraceInfo} 没有 {@code getMethodName()} / {@code getClassName()}
     *       （per Ruling 1），会编译失败。</li>
     *   <li>断言弱：null 短路 + noneMatch — 即使测试本身出错（比如 harness
     *       启动失败返回 null）也能"通过"。</li>
     * </ol>
     * 修正为：列表非空时精确检查不出现 {@code TestComponent#aspectLogDemo}
     * 节点，且列表整体应当为空（因为 none store 不保留任何东西）。</p>
     */
    @Test
    void none_store_records_no_traces() {
        Map<String, Object> props = new HashMap<>();
        props.put("method-trace-log.log.trace-store.type", "none");
        try (MtlE2eHarness host = MtlE2eHarness.primary(8094, props)) {
            host.http().getForEntity(
                    "http://localhost:8094/test/aspectLog?name=store-none", String.class);

            // 留时间让 LogAspect / SimpleMonitorServiceImpl 把事件处理完。
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            List<MethodTraceInfo> roots = fetchRoots(host, 50);
            assertThat(roots)
                    .as("with trace-store.type=none, /view/list should return empty list "
                            + "(NoOpTraceStore.getRecent returns List.of(); save is a no-op)")
                    .isNotNull()
                    .isEmpty();

            // 双重断言：即便列表非空（比如其它 bean 误写），也不应出现
            // TestComponent#aspectLogDemo 这一对节点。
            if (roots != null) {
                boolean hasTarget = roots.stream().anyMatch(r ->
                        r != null && r.getBefore() != null
                                && ASPECT_LOG_DEMO.equals(r.getBefore().getMethodName())
                                && TEST_COMPONENT_FQN.equals(r.getBefore().getClassName()));
                assertThat(hasTarget)
                        .as("with trace-store.type=none, /view/list must not contain "
                                + "any TestComponent#aspectLogDemo root; got: " + roots)
                        .isFalse();
            }
        }
    }
}
