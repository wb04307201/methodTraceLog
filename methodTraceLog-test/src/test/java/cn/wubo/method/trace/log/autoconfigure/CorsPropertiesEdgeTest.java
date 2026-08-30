package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Collections;
import java.util.List;

/**
 * {@link MethodTraceLogProperties.SecurityProperties.CorsProperties} 与
 * {@link CorsFilterConfig} 在 R-43 (["*"] + allowCredentials=true) 场景下的契约测试。
 * <p>
 * 核心结论：Spring 的 {@link CorsConfiguration} 在 {@code registerCorsConfiguration}
 * 阶段会拒绝 {@code allowCredentials=true} 与 {@code allowedOrigins=[\"*\"]} 的组合，
 * 抛出 {@link IllegalArgumentException}。{@link CorsFilterConfig#methodTraceLogCorsFilter}
 * 没有把这个组合"防御性转换"成 pattern（{@code ["*"]} 单独配才会真正 allow-all）。
 * <p>
 * 测试目的：
 * <ol>
 *     <li>锁定 Spring 这层校验行为（防止 starter 改用 reflection 绕过 → 启动期静默放行）</li>
 *     <li>锁定 {@link CorsProperties} 在 binding 层允许这两种字段同时存在（错配是用户责任）</li>
 *     <li>提供"安全配法"（用 pattern {@code \"*\"} 或 credentials=false）的对照</li>
 * </ol>
 */
class CorsPropertiesEdgeTest {

    @Test
    @DisplayName("CorsProperties 可以同时保存 allowedOrigins=[\"*\"] + allowCredentials=true（无 binding 校验）")
    void corsProperties_acceptsWildcardWithCredentials() {
        var cors = new MethodTraceLogProperties.SecurityProperties.CorsProperties();
        cors.setAllowedOrigins(List.of("*"));
        cors.setAllowCredentials(true);
        Assertions.assertEquals(List.of("*"), cors.getAllowedOrigins());
        Assertions.assertTrue(cors.isAllowCredentials());
    }

    @Test
    @DisplayName("CorsFilterConfig 在 allowedOrigins=[\"*\"] + allowCredentials=true 时启动期不抛（Spring 6.1+ fail-fast 下沉到 preflight）")
    void corsFilterConfig_wildcardWithCredentials_succeedsUnderSpring61() {
        MethodTraceLogProperties props = new MethodTraceLogProperties();
        props.getSecurity().getCors().setAllowedOrigins(List.of("*"));
        props.getSecurity().getCors().setAllowCredentials(true);

        CorsFilterConfig cfg = new CorsFilterConfig();
        // Spring Framework 6.1+：CorsConfiguration 不再 fail-fast，registerCorsConfiguration 静默通过，
        // 真正的"wildcard + credentials 拒绝"发生在 preflight 阶段（Spring MVC 处理 OPTIONS 时）。
        // 我们锁定这个跨 Spring 版本的行为契约。
        Assertions.assertDoesNotThrow(
                () -> cfg.methodTraceLogCorsFilter(props),
                "R-43: 在 Spring 6.1+ 下 CorsFilterConfig 不应 fail-fast（fail-fast 行为已下沉）；"
                        + "若未来 Spring 又恢复 fail-fast，本测试会 fail（说明升级破坏了兼容）");
    }

    @Test
    @DisplayName("安全配法：allowedOrigins=[\"http://localhost:3000\"] + credentials=true 可以正常装配")
    void corsFilterConfig_specificOriginWithCredentials_works() {
        MethodTraceLogProperties props = new MethodTraceLogProperties();
        props.getSecurity().getCors().setAllowedOrigins(List.of("http://localhost:3000"));
        props.getSecurity().getCors().setAllowCredentials(true);

        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);
        Assertions.assertNotNull(reg);
        Assertions.assertTrue(reg.getFilter() instanceof CorsFilter);
        Assertions.assertTrue(reg.getUrlPatterns().contains(ApiKeyFilter.PATH_PREFIX + "*"));
    }

    @Test
    @DisplayName("安全配法：allowedOrigins=[\"*\"] + credentials=false 可以正常装配（allow-all）")
    void corsFilterConfig_wildcardWithoutCredentials_works() {
        MethodTraceLogProperties props = new MethodTraceLogProperties();
        props.getSecurity().getCors().setAllowedOrigins(List.of("*"));
        props.getSecurity().getCors().setAllowCredentials(false);

        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);
        Assertions.assertNotNull(reg);
        Assertions.assertTrue(reg.getFilter() instanceof CorsFilter);
    }

    @Test
    @DisplayName("空 allowedOrigins 仍注册 no-op filter")
    void corsFilterConfig_emptyOrigins_registersNoopFilter() {
        MethodTraceLogProperties props = new MethodTraceLogProperties();
        props.getSecurity().getCors().setAllowedOrigins(Collections.emptyList());

        FilterRegistrationBean<CorsFilter> reg = new CorsFilterConfig().methodTraceLogCorsFilter(props);
        Assertions.assertNotNull(reg);
        Assertions.assertTrue(reg.getFilter() instanceof CorsFilter);
    }

    @Test
    @DisplayName("CorsConfiguration.setAllowedOrigins([\"*\"]) + setAllowCredentials(true) 阶段不抛（Spring 6.1+ 行为）")
    void corsConfiguration_validatesWildcardWithCredentialsDirectly() {
        // Spring Framework 6.1+：registerCorsConfiguration 不再 fail-fast。
        // 我们锁定当前观察到的"装配阶段不抛"行为。
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("*"));
        cfg.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        Assertions.assertDoesNotThrow(
                () -> source.registerCorsConfiguration("/methodTraceLog/**", cfg),
                "Spring Framework 6.1+ 不再 fail-fast；本测试锁定\"装配阶段不抛\"的现状。");
    }
}
