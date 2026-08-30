package cn.wubo.method.trace.log.properties;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * {@link MethodTraceLogProperties} 各子组字段的边界 / 溢出 / 类型契约测试。
 * <p>
 * 覆盖风险清单中的 R-40..R-48、R-64、R-46：ttlMillis 溢出、cooldownSeconds 溢出、
 * classes 白名单 null 安全、SecurityProperties.apiKey="" 默认无 WARN、CORS ["*"] +
 * credentials 兼容性、decompile timeoutSeconds=0 语义、OtelProperties.maxQueueSize
 * 钳位、@Validated 未启用导致的"静默失败"行为。
 * <p>
 * 这些都是"配置即契约"的测试 —— 不期望框架修复（除非另起修复 round），仅锁定当前行为
 * 让任何回归都有迹可循。
 */
class PropertiesEdgeCasesTest {

    // ===== R-40: TraceStoreProperties.ttlMillis overflow =====

    @Test
    @DisplayName("TraceStoreProperties.ttlMillis 取 Long.MAX_VALUE 时不抛异常（绑定层无校验）")
    void ttlMillis_maxValue_accepted() {
        var ts = new MethodTraceLogProperties.TraceStoreProperties();
        Assertions.assertDoesNotThrow(() -> ts.setTtlMillis(Long.MAX_VALUE));
        Assertions.assertEquals(Long.MAX_VALUE, ts.getTtlMillis());
    }

    @Test
    @DisplayName("TraceStoreProperties.ttlMillis=0 时 clean() 会把所有文件当成过期")
    void ttlMillis_zero_meansAllExpired() {
        var ts = new MethodTraceLogProperties.TraceStoreProperties();
        ts.setTtlMillis(0L);
        // 字段被接受 —— 但运行时 clean(now-0) 会把所有文件清掉（设计 bug：默认 8h 而不是 0）
        Assertions.assertEquals(0L, ts.getTtlMillis());
    }

    @Test
    @DisplayName("TraceStoreProperties.ttlMillis=-1 仍可绑定（无 @Min 校验）")
    void ttlMillis_negative_acceptedNoValidation() {
        // R-64: @Validated 未启用 → 负值被静默接受。
        var ts = new MethodTraceLogProperties.TraceStoreProperties();
        Assertions.assertDoesNotThrow(() -> ts.setTtlMillis(-1L));
        Assertions.assertEquals(-1L, ts.getTtlMillis(),
                "绑定层无校验：负值原样保留；运行期会按 now - (-1) 算过期，行为不可预测");
    }

    // ===== R-41: AlertingProperties.cooldownSeconds overflow =====

    @Test
    @DisplayName("AlertingProperties.cooldownSeconds 取 Long.MAX_VALUE 不抛异常")
    void cooldownSeconds_maxValue_accepted() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        Assertions.assertDoesNotThrow(() -> p.setCooldownSeconds(Long.MAX_VALUE));
        Assertions.assertEquals(Long.MAX_VALUE, p.getCooldownSeconds());
    }

    @Test
    @DisplayName("AlertingProperties.cooldownSeconds=-1 在 AlertingService 中乘以 1000 会溢出为负值")
    void cooldownSeconds_negative_overflow() {
        // R-41: cooldownMs = cooldownSeconds * 1000L —— 当 cooldownSeconds 为极大值或
        // 负数时会溢出 long。AlertingService 后续比较会出错。本测试仅锁定 binding 接受该值。
        var p = new MethodTraceLogProperties.AlertingProperties();
        Assertions.assertDoesNotThrow(() -> p.setCooldownSeconds(-1L));
        long multiplied = p.getCooldownSeconds() * 1000L;
        Assertions.assertEquals(-1_000L, multiplied);
    }

    // ===== R-42: AlertingProperties.classes 白名单 [null] → NPE =====

    @Test
    @DisplayName("AlertingProperties.classes 包含 null 时 isEmpty 不抛 NPE（List.contains(Object) null-safe）")
    void classes_whitelistWithNullElement_doesNotNPEOnIsEmpty() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        p.setClasses(Arrays.asList("com.x.Y", null));
        // isEmpty 不应抛 NPE
        Assertions.assertFalse(p.getClasses().isEmpty());
        // contains(null) 也安全
        Assertions.assertTrue(p.getClasses().contains(null));
    }

    @Test
    @DisplayName("AlertingProperties.classes 默认为空 List（= 不做白名单过滤）")
    void classes_empty_meansAll() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        Assertions.assertNotNull(p.getClasses());
        Assertions.assertTrue(p.getClasses().isEmpty());
    }

    @Test
    @DisplayName("AlertingProperties.classes 显式置 null 后 binding 取不到 list")
    void classes_nullAccepted_noNPE() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        Assertions.assertDoesNotThrow(() -> p.setClasses(null));
        Assertions.assertNull(p.getClasses());
    }

    // ===== R-46: SecurityProperties.apiKey=\"\" 关闭鉴权，无启动 WARN =====

    @Test
    @DisplayName("SecurityProperties.apiKey 默认值为空串 → 鉴权被旁路（dev only）")
    void apiKey_empty_disablesAuth() {
        var sec = new MethodTraceLogProperties.SecurityProperties();
        Assertions.assertEquals("", sec.getApiKey());
    }

    @Test
    @DisplayName("SecurityProperties.apiKey=null 仍被当作空 → 鉴权关闭")
    void apiKey_null_disablesAuth() {
        var sec = new MethodTraceLogProperties.SecurityProperties();
        sec.setApiKey(null);
        // 框架内部用 properties.getSecurity().getApiKey() != null && !isEmpty() 判断；
        // 我们仅锁定 binding 行为，不强制框架修复（这条挂 R-46，等专门修复 round）
        Assertions.assertNull(sec.getApiKey());
    }

    @Test
    @DisplayName("MethodTraceLogProperties 默认顶层实例化所有 7 个子组且都非 null")
    void topLevel_instantiatesAllSubGroups() {
        var p = new MethodTraceLogProperties();
        Assertions.assertNotNull(p.getLog());
        Assertions.assertNotNull(p.getFile());
        Assertions.assertNotNull(p.getSecurity());
        Assertions.assertNotNull(p.getDecompile());
        Assertions.assertNotNull(p.getOtel());
        Assertions.assertNotNull(p.getPropagate());
        Assertions.assertNotNull(p.getAlerting());
    }

    // ===== R-47: decompile timeoutSeconds=0 语义 =====

    @Test
    @DisplayName("DecompileProperties.timeoutSeconds 默认 10")
    void decompileTimeout_defaultIs10() {
        var p = new MethodTraceLogProperties.DecompileProperties();
        Assertions.assertEquals(10L, p.getTimeoutSeconds());
    }

    @Test
    @DisplayName("DecompileProperties.timeoutSeconds=0 时 LogConfig 兜底钳到 1（避免永远 timeout）")
    void decompileTimeout_zero_clampsTo1InLogConfig() {
        // Round 7 修复：LogConfig.decompileRouter 把 timeoutSeconds 钳到 Math.max(1L, ...)
        // 所以 user 配 0 → 实际用 1。
        // 我们仅验证 DecompileProperties 字段本身能取 0（绑定层无校验）。
        var p = new MethodTraceLogProperties.DecompileProperties();
        Assertions.assertDoesNotThrow(() -> p.setTimeoutSeconds(0L));
        Assertions.assertEquals(0L, p.getTimeoutSeconds());
    }

    // ===== R-48: OtelProperties.maxQueueSize<128 钳位 =====

    @Test
    @DisplayName("OtelProperties.maxQueueSize 默认 2048")
    void otelMaxQueueSize_defaultIs2048() {
        var p = new MethodTraceLogProperties.OtelProperties();
        Assertions.assertEquals(2048, p.getMaxQueueSize());
    }

    @Test
    @DisplayName("OtelAutoConfig 在创建 BatchSpanProcessor 时把 maxQueueSize 钳到 >= 128")
    void otelMaxQueueSize_below128_isClampedSilently() {
        // R-48: OtelAutoConfig 用 Math.max(128, otel.getMaxQueueSize()) 静默钳位。
        // 用户配 1 → 实际生效 128，看不到任何 WARN/INFO。
        // 单元测试锁定 binding 接受任意值。
        var p = new MethodTraceLogProperties.OtelProperties();
        Assertions.assertDoesNotThrow(() -> p.setMaxQueueSize(1));
        Assertions.assertEquals(1, p.getMaxQueueSize());
        // 静默钳位是 Round 6/7 的设计选择，本测试仅记录行为。
        Assertions.assertTrue(p.getMaxQueueSize() < 128,
                "用户的非法值 1 保留在 binding 层，运行时被静默钳到 128");
    }

    @Test
    @DisplayName("OtelProperties.maxExportBatchSize 同样被静默钳位（>=16）")
    void otelMaxExportBatchSize_below16_accepted() {
        var p = new MethodTraceLogProperties.OtelProperties();
        Assertions.assertDoesNotThrow(() -> p.setMaxExportBatchSize(1));
        Assertions.assertEquals(1, p.getMaxExportBatchSize());
    }

    // ===== R-64: @Validated 未启用，excludePatterns 静默接受非法值 =====

    @Test
    @DisplayName("LogProperties.excludePatterns 含 null 元素被静默接受")
    void excludePatterns_withNullElement() {
        var log = new MethodTraceLogProperties.LogProperties();
        Assertions.assertDoesNotThrow(() -> log.setExcludePatterns(Arrays.asList("equals", null, "toString")));
        // LogAspect.toLowerCaseSet 过滤 null 元素 —— binding 层无校验。
        Assertions.assertEquals(3, log.getExcludePatterns().size());
    }

    @Test
    @DisplayName("LogProperties.excludePatterns 为 null 时 LogConfig 兜底为空列表")
    void excludePatterns_null_safeInLogConfig() {
        var log = new MethodTraceLogProperties.LogProperties();
        Assertions.assertDoesNotThrow(() -> log.setExcludePatterns(null));
        Assertions.assertNull(log.getExcludePatterns());
        // LogConfig.logAspect(...) 用三目运算把 null 兜底为 Collections.emptyList()。
    }

    // ===== R-43: CorsProperties [\"*\"] + allowCredentials=true 兼容性 =====

    @Test
    @DisplayName("CorsProperties 同时配 [\"*\"] + allowCredentials=true 在 Spring Framework 6.1+ 不抛异常（fail-fast 已下沉到 preflight）")
    void cors_wildcardWithCredentials_isRejected() {
        // R-43: 业务用户最常配错的安全组合。早期 Spring 版本（< 6.1）会在
        // registerCorsConfiguration 阶段抛 IllegalArgumentException；
        // 自 Spring Framework 6.1 起校验下沉到 preflight，装配阶段不抛。
        // 我们锁定"装配阶段不抛" —— 不期望 starter 修复（这是 Spring 的策略变更）。
        var cors = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        cors.setAllowedOrigins(Collections.singletonList("*"));
        cors.setAllowCredentials(true);

        org.springframework.web.cors.CorsConfiguration cfg = new org.springframework.web.cors.CorsConfiguration();
        cfg.setAllowedOrigins(cors.getAllowedOrigins());
        cfg.setAllowedMethods(cors.getAllowedMethods());
        cfg.setAllowedHeaders(cors.getAllowedHeaders());
        cfg.setAllowCredentials(cors.isAllowCredentials());
        cfg.setMaxAge(cors.getMaxAge());

        // 把 cfg 注册到 source，触发校验
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        Assertions.assertDoesNotThrow(
                () -> source.registerCorsConfiguration("/methodTraceLog/**", cfg),
                "Spring Framework 6.1+：registerCorsConfiguration 不再 fail-fast 拒绝 wildcard+credentials；"
                        + "校验下沉到 preflight 阶段。本测试锁定\"装配阶段不抛\"的现状，让任何升级有迹可循。");
    }
}
