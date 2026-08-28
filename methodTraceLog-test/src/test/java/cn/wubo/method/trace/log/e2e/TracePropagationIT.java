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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双实例端到端：验证 starter 的 W3C traceparent 出站 + 入站机制确实让跨实例调用
 * 共享同一个 traceid。
 *
 * <p>拓扑：
 * <pre>
 *   secondary (8086) -- /test/callRemote --> primary (8085) -- /test/aspectLog
 *   [callRemote span]                      [aspectLog span
 *                                            + aspectLogDemo child]
 * </pre>
 *
 * <p>两个进程都注册了 starter：
 * <ul>
 *   <li>secondary 的 {@code /test/callRemote}（RestClient 变体）和
 *       {@code /test/callRemoteRestTemplate}（RestTemplate 变体）会通过
 *       {@link cn.wubo.method.trace.log.autoconfigure.TraceContextRestClientCustomizer}
 *       / {@link cn.wubo.method.trace.log.autoconfigure.TraceContextRestTemplateInterceptor}
 *       在出站请求上注入 {@code traceparent} 头。</li>
 *   <li>primary 的
 *       {@link cn.wubo.method.trace.log.autoconfigure.TraceContextFilter}
 *       从入站请求头里恢复 traceid 写入 MDC，让 LogAspect 用同一个 traceid
 *       记录入站 span。</li>
 * </ul>
 *
 * <p>注意 {@code MethodTraceInfo} 上没有 {@code getMethodName()} — 方法名在
 * {@code getBefore().getMethodName()} 上。所有断言都走那条路径，并对
 * {@code getBefore()} 做空检查。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracePropagationIT {

    private MtlE2eHarness primary;   // 8085
    private MtlE2eHarness secondary; // 8086

    @BeforeAll
    void setup() {
        primary = MtlE2eHarness.primary(8085, Map.of());
        secondary = MtlE2eHarness.secondary(8086);
    }

    @AfterAll
    void teardown() {
        if (secondary != null) secondary.close();
        if (primary != null) primary.close();
    }

    /**
     * 把 /methodTraceLog/view/list 反序列化成真正的 {@link MethodTraceInfo} 列表。
     * 直接用 {@code List.class} 会拿到 {@code List<LinkedHashMap>}，再调用
     * {@code getBefore().getMethodName()} 会抛 ClassCastException。
     */
    private List<MethodTraceInfo> fetchTraceList(MtlE2eHarness h, int limit) {
        ParameterizedTypeReference<List<MethodTraceInfo>> typeRef = new ParameterizedTypeReference<List<MethodTraceInfo>>() {};
        ResponseEntity<List<MethodTraceInfo>> resp = h.http().getRestTemplate().exchange(
                "http://localhost:" + h.port() + "/methodTraceLog/view/list?limit=" + limit,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                typeRef);
        return resp.getBody();
    }

    /**
     * 轮询 host 的根 trace 列表，直到出现一个根，其 BEFORE 事件的 methodName
     * 等于 {@code methodName} 为止。
     */
    private List<MethodTraceInfo> awaitTraceListContaining(MtlE2eHarness h, String methodName, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MethodTraceInfo> list = fetchTraceList(h, 50);
                boolean found = list != null && list.stream().anyMatch(r ->
                        r != null && r.getBefore() != null && methodName.equals(r.getBefore().getMethodName()));
                if (found) return list;
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
                "Trace with methodName=" + methodName + " did not appear within " + timeout,
                lastError);
    }

    /**
     * 找到列表里 methodName 匹配的第一个节点（按出现顺序，第一个就是最新）。
     * 返回 Optional.empty() 时表示没找到。
     */
    private Optional<MethodTraceInfo> findRootByMethodName(List<MethodTraceInfo> roots, String methodName) {
        return roots.stream()
                .filter(r -> r != null && r.getBefore() != null && methodName.equals(r.getBefore().getMethodName()))
                .findFirst();
    }

    /**
     * 把 traceid 标准化成 32 位无连字符 hex。
     * 出站侧（secondary）LogAspect 生成的是 {@code UUID.randomUUID().toString()}（带连字符，36 字符）；
     * 入站侧（primary）的 TraceContextFilter 从 traceparent 头恢复的是去掉连字符的 32 hex。
     * 跨实例比较时必须先规范化，否则会误判不相等。
     */
    private static String normalizeTraceid(String tid) {
        if (tid == null) return null;
        String hex = tid.replace("-", "");
        if (hex.length() < 32) {
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 32 - hex.length(); i++) sb.append('0');
            sb.append(hex);
            return sb.toString();
        }
        return hex.substring(0, 32);
    }

    @Test
    void restclient_propagates_trace_id_across_instances() {
        // Outbound via RestClient from secondary (8086) → primary (8085) /test/aspectLog
        secondary.http().getForEntity(
                "http://localhost:8086/test/callRemote?port=8085&name=propagate-rc",
                String.class);

        // Secondary 应该记录自己的出站调用 — 根 methodName = callRemote。
        // 该根节点的 traceid 应当被 TraceContextRestClientCustomizer 写入 traceparent 头，
        // 然后被 primary 端的 TraceContextFilter 恢复出来。
        List<MethodTraceInfo> secondaryRoots = awaitTraceListContaining(
                secondary, "callRemote", Duration.ofSeconds(5));
        Optional<MethodTraceInfo> secondaryOutbound = findRootByMethodName(secondaryRoots, "callRemote");
        assertThat(secondaryOutbound)
                .as("secondary should record the outbound callRemote call")
                .isPresent();
        String sharedTraceid = normalizeTraceid(secondaryOutbound.get().getBefore().getTraceid());
        assertThat(sharedTraceid).as("secondary outbound traceid").isNotNull();

        // Primary 应该收到同一个 traceid 的入站调用。
        // 入站根 methodName = aspectLog（TestController 端点方法名），
        // 它下面挂着 TestComponent.aspectLogDemo（被 @AspectLog 改名为 aspectLogDemo）。
        List<MethodTraceInfo> primaryRoots = fetchTraceList(primary, 50);
        Optional<MethodTraceInfo> primaryInbound = primaryRoots.stream()
                .filter(r -> r != null && r.getBefore() != null
                        && sharedTraceid.equals(normalizeTraceid(r.getBefore().getTraceid())))
                .findFirst();
        assertThat(primaryInbound)
                .as("primary should have a trace matching secondary's outbound traceid")
                .isPresent();
        assertThat(primary.findInTrace(primaryInbound.get(), "aspectLogDemo"))
                .as("inbound trace tree should contain aspectLogDemo child (via @AspectLog rename)")
                .isPresent();
    }

    @Test
    void resttemplate_propagates_trace_id_across_instances() {
        // Outbound via RestTemplate from secondary (8086) → primary (8085) /test/aspectLog
        secondary.http().getForEntity(
                "http://localhost:8086/test/callRemoteRestTemplate?port=8085&name=propagate-rt",
                String.class);

        // Secondary 应该记录自己的出站调用 — 根 methodName = callRemoteRestTemplate。
        // 该根的 traceid 由 TraceContextRestTemplateInterceptor 写入 traceparent。
        List<MethodTraceInfo> secondaryRoots = awaitTraceListContaining(
                secondary, "callRemoteRestTemplate", Duration.ofSeconds(5));
        Optional<MethodTraceInfo> secondaryOutbound = findRootByMethodName(secondaryRoots, "callRemoteRestTemplate");
        assertThat(secondaryOutbound)
                .as("secondary should record the outbound callRemoteRestTemplate call")
                .isPresent();
        String sharedTraceid = normalizeTraceid(secondaryOutbound.get().getBefore().getTraceid());
        assertThat(sharedTraceid).as("secondary outbound traceid").isNotNull();

        // Primary 应该收到同一个 traceid 的入站调用。
        List<MethodTraceInfo> primaryRoots = fetchTraceList(primary, 50);
        Optional<MethodTraceInfo> primaryInbound = primaryRoots.stream()
                .filter(r -> r != null && r.getBefore() != null
                        && sharedTraceid.equals(normalizeTraceid(r.getBefore().getTraceid())))
                .findFirst();
        assertThat(primaryInbound)
                .as("primary should have a trace matching secondary's outbound traceid (RestTemplate)")
                .isPresent();
        assertThat(primary.findInTrace(primaryInbound.get(), "aspectLogDemo"))
                .as("inbound trace tree should contain aspectLogDemo child (RestTemplate path)")
                .isPresent();
    }
}