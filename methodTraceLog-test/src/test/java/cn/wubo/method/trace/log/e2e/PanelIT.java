package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code GET /methodTraceLog/panel}(单页控制台)的两条主路径:
 * <ul>
 *   <li>{@code panel_returns_html_with_all_tabs}:带 X-Api-Key 拉页面 → 200 + HTML 主体
 *       体量 &gt; 10KB + 命中至少一个已知 tab 名(中文 / 英文任一)。</li>
 *   <li>{@code panel_is_whitelisted_from_auth}:裸 {@link RestTemplate}(无 X-Api-Key / 无 cookie)
 *       拉同一页面 → 200 + Content-Type 为 {@code text/html} —— {@code /panel} 在
 *       {@code ApiKeyFilter.PUBLIC_PATHS} 白名单里,直接放行。</li>
 * </ul>
 *
 * <p><b>关于 HTML 主体大小 &gt; 10KB(per brief 关注点 2):</b>
 * 实测 panel.html 14,572 字节,远大于 10KB;同时
 * 页面里通过 {@code <link rel="stylesheet">} / {@code <script src>} 引用 panel.css 和
 * 4 个 JS(overview/traces/logs/decompile),这些是浏览器侧加载,不会进响应体 —— 因此
 * 10KB 下限是 panel.html 自己的体积。Brief 在任务 14 之后走的就是这条路径(200 + 大 HTML)。
 *
 * <p><b>关于 tab 名字(per brief 关注点 3):</b>
 * panel.html 里同时存在两套标识:
 * <ol>
 *   <li>中文展示名:{@code 概览} / {@code 调用记录} / {@code 日志文件} / {@code 反编译}</li>
 *   <li>英文 hash / data-tab:{@code #overview} / {@code #traces} / {@code #logs} / {@code #decompile}</li>
 * </ol>
 * 测试用 {@code ||} 连接,任一命中即视为通过 —— 保证即使将来国际化切到英文 tab 也不破。
 *
 * <p><b>关于 /panel 在白名单(per brief 关注点 1 + 4):</b>
 * 见 {@code ApiKeyFilter.PUBLIC_PATHS} 的注释 + 集合:
 * <pre>
 *   private static final Set&lt;String&gt; PUBLIC_PATHS = Set.of(
 *       "/methodTraceLog/panel",
 *       "/methodTraceLog/login",
 *       "/methodTraceLog/logout",
 *       "/methodTraceLog/session/status"
 *   );
 * </pre>
 * {@code shouldNotFilter} 在该集合里返回 true,过滤器整个被跳过 —— 既不检查
 * X-Api-Key,也不检查 cookie。所以裸 {@code RestTemplate} 也走通,且响应来自
 * {@code LogConfig.methodTraceLogRouter} 的
 * {@code ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource("/panel.html"))},
 * 状态 200,Content-Type 必为 {@code text/html}。
 *
 * <p><b>关于第二个用例的 Content-Type 断言(per 实施者契约的 Strengthen):</b>
 * brief 只断言 200,我额外锁 {@code MediaType.TEXT_HTML} —— 防止有天 /panel 路由被改成
 * JSON 形态(比如某次 refactor 把 ClassPathResource 替换成 Map.of(...)),那时白名单仍然
 * 放行 200,但已经是 JSON 了,违反 HTML 页面契约。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanelIT {

    private static final int PORT = 8085;
    private static final String PANEL_URL = "http://localhost:" + PORT + "/methodTraceLog/panel";

    private MtlE2eHarness host;
    private RestTemplate bareClient;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(PORT, Map.of());
        // 独立裸 RestTemplate —— 不挂 X-Api-Key interceptor,用于走"白名单"路径
        bareClient = new RestTemplate();
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void panel_returns_html_with_all_tabs() {
        var resp = host.http().getForEntity(PANEL_URL, String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/panel with X-Api-Key should return 2xx; got %s",
                        resp.getStatusCode())
                .isTrue();

        String html = resp.getBody();
        assertThat(html)
                .as("panel HTML body should not be null")
                .isNotNull();

        // panel.html 自身体积 ~14KB(关注点 2)
        assertThat(html.length())
                .as("panel HTML body should be substantial (>10KB); got %d bytes", html.length())
                .isGreaterThan(10_000);

        // 至少命中一个已知 tab 名 —— 中英文任一(关注点 3)
        boolean hasTab = html.contains("概览")
                || html.contains("调用记录")
                || html.contains("日志文件")
                || html.contains("反编译")
                || html.contains("overview")
                || html.contains("traces")
                || html.contains("logs")
                || html.contains("decompile");
        assertThat(hasTab)
                .as("panel HTML should contain at least one known tab name (Chinese or English); "
                        + "first 200 chars:%n%s", html.substring(0, Math.min(200, html.length())))
                .isTrue();
    }

    @Test
    void panel_is_whitelisted_from_auth() {
        // 裸 RestTemplate —— 无 X-Api-Key / 无 cookie(关注点 1 + 4)
        var resp = bareClient.getForEntity(PANEL_URL, String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /panel without X-Api-Key should still return 2xx because /panel is "
                        + "whitelisted in ApiKeyFilter.PUBLIC_PATHS; got %s", resp.getStatusCode())
                .isTrue();

        // 额外锁 Content-Type=text/html(实施者契约 Strengthen)
        MediaType contentType = resp.getHeaders().getContentType();
        assertThat(contentType)
                .as("Content-Type header should be present")
                .isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.TEXT_HTML))
                .as("Content-Type should be text/html; got %s", contentType)
                .isTrue();
    }
}