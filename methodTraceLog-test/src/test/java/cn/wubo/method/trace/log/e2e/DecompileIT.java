package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 {@code GET /methodTraceLog/decompile}(CFR 反编译端点)的两条主路径:
 * <ul>
 *   <li>已存在的方法 -&gt; 200 + 只含该方法的 Java 源码</li>
 *   <li>不存在的方法 -&gt; 404(由 {@code DecompilerUtils.extractMethod} 返回 empty 触发
 *       {@code ResponseStatusException(NOT_FOUND)},见 {@code LogConfig.decompileRouter})</li>
 * </ul>
 *
 * <p><b>为什么断言签名而不是裸 "hello"(per brief 关注点 3):</b>
 * {@code contains("hello")} 太弱 —— javadoc、字段名、甚至 {@code hello1/hello2} 的调用点
 * 都能让它通过。这里断言 {@code public String hello(} 的签名形态,证明 CFR 真的产出了
 * 方法签名,而不是碰巧提到了 "hello"。用 regex 而非字面量是为了容忍 CFR 版本间的空白差异。</p>
 *
 * <p><b>关于 AOP 代理(per brief 关注点 2):</b>
 * {@code TestService} 带 {@code @Service},运行时被 CGLIB 代理。但
 * {@code DecompilerUtils.decompile} 走的是 {@code Class.forName(className, false, ctxCl)}
 * + {@code getResourceAsStream},拿到的是磁盘上的原始 {@code TestService.class} 字节,
 * 与代理无关。所以反编译结果必须是原始方法体 —— 下面用 {@code testComponent} 的委托调用
 * 来锁死这一点(代理类的 hello 只会是 {@code MethodProxy} 转发,不含该字段引用)。</p>
 *
 * <p><b>关于 404 的 try/catch(per brief 关注点 4):</b>
 * {@link org.springframework.boot.test.web.client.TestRestTemplate} 装的是
 * {@code NoOpResponseErrorHandler},4xx 不抛异常而是正常返回 ResponseEntity。
 * 但如果哪天 harness 换成裸 {@code RestTemplate},就会抛 {@link HttpClientErrorException}。
 * 两条路都断言 404,保证测试语义不随 HTTP client 实现漂移。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecompileIT {

    private static final int PORT = 8085;
    private static final String TARGET_CLASS = "cn.wubo.method.trace.log.TestService";

    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(PORT, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void decompile_known_method_returns_source() {
        var resp = host.http().getForEntity(
                "http://localhost:" + PORT + "/methodTraceLog/decompile"
                        + "?className=" + TARGET_CLASS + "&methodName=hello",
                String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /methodTraceLog/decompile for an existing method should return 2xx; got %s body=%s",
                        resp.getStatusCode(), resp.getBody())
                .isTrue();

        String body = resp.getBody();
        assertThat(body)
                .as("decompiled body should not be null/blank")
                .isNotNull()
                .isNotBlank();

        // 关注点 3:断言方法签名,而不是任意一处 "hello" 字样。
        assertThat(body)
                .as("CFR output should contain the real method signature 'public String hello(...)'; got:%n%s", body)
                .containsPattern("public\\s+String\\s+hello\\s*\\(");

        // 关注点 2:验证反编译的是原始字节码而非 CGLIB 代理 —— 原始 hello 委托给 testComponent。
        assertThat(body)
                .as("decompiled body should be the ORIGINAL method body (delegating to testComponent), "
                        + "not a CGLIB proxy forward; got:%n%s", body)
                .contains("testComponent");

        // extractMethod 生效的证据:只回目标方法,不回整个类壳(旧版 fallback 行为)。
        assertThat(body)
                .as("response should be the extracted method only, not the whole class shell; got:%n%s", body)
                .doesNotContain("class TestService");
    }

    @Test
    void decompile_unknown_method_returns_404() {
        try {
            var resp = host.http().getForEntity(
                    "http://localhost:" + PORT + "/methodTraceLog/decompile"
                            + "?className=" + TARGET_CLASS + "&methodName=doesNotExist",
                    String.class);
            assertThat(resp.getStatusCode().value())
                    .as("unknown methodName should yield 404 (extractMethod -> empty -> "
                            + "ResponseStatusException(NOT_FOUND)); got %s body=%s",
                            resp.getStatusCode(), resp.getBody())
                    .isEqualTo(404);
        } catch (HttpClientErrorException e) {
            // 只有在 harness 改用会抛 4xx 的 RestTemplate 时才会走到这里。
            assertThat(e.getStatusCode().value())
                    .as("unknown methodName should yield 404; got %s", e.getStatusCode())
                    .isEqualTo(404);
        }
    }
}
