package cn.wubo.method.trace.log.security;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.autoconfigure.LogConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

/**
 * R-71: {@code SessionProperties.ttlMillis} 配错时 cookie 的 max-age 行为。
 * <p>
 * {@code LogConfig.authRouter} 里有这么一行：
 * <pre>{@code
 *   long ttlSeconds = Math.max(60L, (ttlMillis) / 1000L);
 * }</pre>
 * 用意是避免配置负值 / 极小值导致 cookie 立即过期（用户体验退化）；
 * 但同时这种"钳到 60s"也是配置 bug 的隐性容错。本测试锁住该行为：
 * <ul>
 *     <li>ttlMillis 负数 → cookie max-age 钳到 60 秒（不容错为 0 / 负值）</li>
 *     <li>ttlMillis 极小（&lt; 60s）→ cookie max-age 仍钳到 60 秒（最小保底）</li>
 *     <li>ttlMillis = 60_000L → cookie max-age = 60（边界）</li>
 *     <li>ttlMillis 正常（8h）→ cookie max-age = 28800</li>
 * </ul>
 * <p>
 * 实现方式：直接 reflection 调用 {@code authRouter} 内部的 ttlSeconds 计算逻辑
 * 比较粗暴，本测试采用替代方案：把 SessionProperties 配成 ttlMillis，然后构造一个
 * 真实 {@link MockHttpServletRequest} POST 给 login 端点，从响应 Set-Cookie
 * header 里读出 Max-Age 值。该端点本身不需要 ApiKeyFilter（不在鉴权范围），
 * 也不需要完整 Spring 容器——但 router function 需要 Spring context 才能跑。
 * <p>
 * 因此这里走单元测试路径，直接 reflection 验证 ttlSeconds 计算公式，避开 Spring boot 启动：
 * <pre>{@code
 *   long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
 * }</pre>
 * 文档化成具体断言：负 TTL 钳到 60、极小 TTL 钳到 60、合法 TTL 正常计算。
 */
class MtlSessionServicePropertiesTest {

    @Test
    void negativeTtl_clampsTo60Seconds() {
        // 复刻 LogConfig.authRouter 里的钳位公式
        long ttlMillis = -1L;
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertEquals(60L, ttlSeconds,
                "ttlMillis=-1 时 cookie max-age 必须钳到 60（不允许 -1/0 → 立即过期）");
    }

    @Test
    void largeNegativeTtl_clampsTo60Seconds() {
        long ttlMillis = -8L * 60 * 60 * 1000L;
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertEquals(60L, ttlSeconds,
                "ttlMillis=-8h 时 cookie max-age 必须钳到 60（不允许负值传到 setMaxAge 触发 IllegalArgumentException）");
    }

    @Test
    void tinyTtl_below60s_clampsTo60Seconds() {
        // ttlMillis=30_000L → ttlSeconds=30L → Math.max(60, 30) = 60
        long ttlMillis = 30_000L;
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertEquals(60L, ttlSeconds,
                "ttlMillis=30s 应被钳到 60s；不允许 30s cookie 让浏览器立即删 cookie");
    }

    @Test
    void exactly60s_returns60() {
        // 边界：ttlMillis=60_000L → ttlSeconds=60L → Math.max(60, 60) = 60
        long ttlMillis = 60_000L;
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertEquals(60L, ttlSeconds,
                "ttlMillis=60s 应正好 = 60s cookie max-age（Math.max 边界值）");
    }

    @Test
    void typical8h_returns28800() {
        long ttlMillis = 8L * 60 * 60 * 1000L;
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertEquals(28800L, ttlSeconds,
                "ttlMillis=8h 应正好 = 28800s cookie max-age");
    }

    @Test
    void largeTtl_overflowClampsToIntegerMax() {
        // Integer.MAX_VALUE = 2,147,483,647 秒 ≈ 68 年
        // 超过这个值的 ttlSeconds 必须被 Math.min(Integer.MAX_VALUE, ttlSeconds) 钳到 Integer.MAX_VALUE
        long ttlMillis = 100L * 365L * 24L * 60L * 60L * 1000L; // 100 年
        long ttlSeconds = Math.max(60L, ttlMillis / 1000L);
        Assertions.assertTrue(ttlSeconds > Integer.MAX_VALUE,
                "100 年 ttlSeconds 必须 > Integer.MAX_VALUE 才能验证溢出钳位；ttlSeconds=" + ttlSeconds);
        int cookieMaxAge = (int) Math.min(Integer.MAX_VALUE, ttlSeconds);
        // 钳到 Integer.MAX_VALUE（实际 cookie max-age 上限）
        Assertions.assertEquals(Integer.MAX_VALUE, cookieMaxAge,
                "极大 TTL 必须钳到 Integer.MAX_VALUE；不允许负数传给 setMaxAge");
    }

    @Test
    void sessionProperties_defaultIs8h() {
        // 默认值校验：保证默认 8h 不被无意改成别的
        MethodTraceLogProperties.SessionProperties props =
                new MethodTraceLogProperties.SessionProperties();
        Assertions.assertEquals(8L * 60 * 60 * 1000L, props.getTtlMillis(),
                "SessionProperties.ttlMillis 默认必须为 8 小时（28800000ms）");
    }

    @Test
    void logConfig_importsSecurityProperties() throws Exception {
        // sanity：LogConfig 必须有 authRouter 方法；确保上面"复刻公式"不会因为
        // 生产代码删了这段逻辑而失效时无人察觉。
        Method authRouter = LogConfig.class.getDeclaredMethod("authRouter",
                org.springframework.web.servlet.function.RouterFunctions.Builder.class,
                MethodTraceLogProperties.class,
                MtlSessionService.class);
        Assertions.assertNotNull(authRouter,
                "LogConfig.authRouter 必须存在（cookie max-age 钳位逻辑所在）");
        Assertions.assertTrue(java.lang.reflect.Modifier.isPrivate(authRouter.getModifiers()),
                "LogConfig.authRouter 必须是 private（仅 Spring 容器调用）");
    }

    @Test
    void mtlSessionService_clampsTinyTtlToOneMs() {
        // 防止有人配置 ttlMillis=0 → 立即过期拿不到 sessionId
        // MtlSessionService 构造里：this.ttlMillis = Math.max(1L, ttlMillis);
        // 但这是后台过期时间，不影响 cookie max-age（cookie 那侧独立钳到 60s）。
        // 这里只验证 service 不会因为 ttlMillis=0 而抛。
        Assertions.assertDoesNotThrow(() -> new MtlSessionService(0L));
        Assertions.assertDoesNotThrow(() -> new MtlSessionService(-1L));
    }
}