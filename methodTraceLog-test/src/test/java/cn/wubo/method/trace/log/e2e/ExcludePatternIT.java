package cn.wubo.method.trace.log.e2e;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code method-trace-log.log.exclude-patterns}：
 * <ul>
 *   <li>{@code equals} / {@code hashCode} / {@code toString}（{@link cn.wubo.method.trace.log.TestLombokEntity}
 *       上 Lombok @Data 生成的方法）应被黑名单短路，{@code LogAspect} 直接 proceed()，
 *       不发出任何 BEFORE / AFTER_* 事件，trace 子树里不应出现这些方法。</li>
 *   <li>{@code describe} / {@code doWork}（{@link cn.wubo.method.trace.log.TestLombokEntity}
 *       上用户定义的方法）不在黑名单里，trace 子树里应当出现。</li>
 * </ul>
 *
 * <p><b>关键路径：</b></p>
 * <ul>
 *   <li>{@code application.yml} 里 {@code method-trace-log.log.exclude-patterns=[equals,
 *       hashCode, toString]}，由 {@code LogConfig.logAspect()} 注入到 {@code LogAspect}
 *       的 3 参构造函数。</li>
 *   <li>{@code LogAspect.around} 拿到方法名后 {@code isExcluded(methodName)}：命中 →
 *       直接 {@code jp.proceed()} 返回，无 traceid / spanid，无事件。</li>
 *   <li>{@code TestController#blacklist} 端点循环 20 次调上述 5 个方法；
 *       {@code setName} / {@code setValue} 不在黑名单 → 应该出现在 trace；{@code equals} /
 *       {@code hashCode} / {@code toString} 在黑名单 → 应该被剔除。</li>
 * </ul>
 *
 * <p><b>关于断言范围（strengthen from brief）：</b></p>
 * <ul>
 *   <li>brief 的 {@code collectNames} 直接 walk 整棵树收集所有 methodName，然后断言
 *       {@code equals/hashCode/toString} 不在集合里 — 但 {@code Map.toString()}、
 *       {@code String.equals} 等其它类上的同名方法可能出现在树里产生 false positive。
 *       真正要排除的是 {@code className == cn.wubo.method.trace.log.TestLombokEntity}
 *       这条路径上的方法名。修正方案：先在 {@code before.className} 上做精确过滤，
 *       只保留 TestLombokEntity 上的方法名。</li>
 *   <li>{@code MethodTraceInfo} 上没有 {@code getMethodName()}（per Ruling 1），
 *       必须走 {@code root.getBefore().getMethodName()}。</li>
 *   <li>{@code /view/list} 返回根 trace 列表；子调用不在根列表里。要拿完整子树，必须
 *       通过 {@code /view/traceid?id=<traceid>} 单独拉。</li>
 *   <li>响应反序列化必须用 {@link ParameterizedTypeReference}（per Ruling 4）：
 *       直接 {@code List.class} 会被 Jackson 解成 {@code List<LinkedHashMap>}，
 *       调 {@code .getBefore()} 会抛 {@code NoSuchMethodError}。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExcludePatternIT {

    private static final String LOMBOK_ENTITY_FQN = "cn.wubo.method.trace.log.TestLombokEntity";

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8085, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    /**
     * Typed fetcher for {@code /methodTraceLog/view/list} — 直接 {@code List.class}
     * 会被 Jackson 解成 {@code List<LinkedHashMap>}（per Ruling 4），调
     * {@code .getBefore()} 时抛 {@code NoSuchMethodError}。这里走
     * {@link ParameterizedTypeReference} 直接落到 {@code List<MethodTraceInfo>}。
     */
    private List<MethodTraceInfo> fetchRoots(int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef =
                new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = host.http().getRestTemplate().exchange(
                "http://localhost:" + host.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                typeRef);
        return resp.getBody();
    }

    /**
     * 轮询根 trace 列表，直到出现 methodName={@code methodName} 的根节点。
     */
    private MethodTraceInfo awaitBlacklistRoot(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> roots = fetchRoots(50);
                if (roots != null) {
                    for (var r : roots) {
                        if (r != null && r.getBefore() != null
                                && "blacklist".equals(r.getBefore().getMethodName())) {
                            return r;
                        }
                    }
                }
            } catch (Exception e) {
                lastError = new AssertionError("Error fetching trace list: " + e.getMessage(), e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(
                "blacklist root trace did not appear within " + timeout,
                lastError);
    }

    /**
     * 递归遍历 {@link MethodTraceInfo} 子树，收集 {@code before.className == targetClass}
     * 的节点上的 {@code before.methodName}。其它类（{@code TestController},
     * {@code LinkedHashMap} 等）上的同名方法会被过滤掉，避免 false positive。
     */
    private void collectLombokMethodNames(MethodTraceInfo node, String targetClass, List<String> sink) {
        if (node == null || node.getBefore() == null) return;
        String cn = node.getBefore().getClassName();
        String mn = node.getBefore().getMethodName();
        if (targetClass.equals(cn) && mn != null) {
            sink.add(mn);
        }
        if (node.getChildren() != null) {
            for (var child : node.getChildren()) {
                collectLombokMethodNames(child, targetClass, sink);
            }
        }
    }

    @Test
    void lombok_generated_methods_are_excluded_but_user_methods_are_not() {
        // 触发一次 /test/blacklist：循环 20 次调 equals/hashCode/toString/describe/doWork/setName/setValue
        host.http().getForEntity(
                "http://localhost:8085/test/blacklist",
                Map.class);

        // 等根节点 methodName=blacklist 出现
        MethodTraceInfo blacklistRoot = awaitBlacklistRoot(Duration.ofSeconds(5));

        // 拿完整 trace 子树（/view/list 只返回根；要看子节点要用 /view/traceid）
        String traceid = blacklistRoot.getBefore().getTraceid();
        MethodTraceInfo fullTree = host.awaitTrace(traceid, Duration.ofSeconds(5));

        // 收集 TestLombokEntity 上的方法名
        List<String> lombokNames = new ArrayList<>();
        collectLombokMethodNames(fullTree, LOMBOK_ENTITY_FQN, lombokNames);

        // 用户自定义方法应出现
        assertThat(lombokNames)
                .as("user-defined TestLombokEntity methods (describe, doWork) should be traced "
                + "because they are NOT in exclude-patterns; collected: " + lombokNames)
                .contains("describe", "doWork");

        // Lombok @Data 生成的方法应被剔除（短路）
        assertThat(lombokNames)
                .as("Lombok-generated TestLombokEntity methods (equals, hashCode, toString) should be "
                + "excluded by method-trace-log.log.exclude-patterns; collected: " + lombokNames)
                .doesNotContain("equals", "hashCode", "toString");
    }
}