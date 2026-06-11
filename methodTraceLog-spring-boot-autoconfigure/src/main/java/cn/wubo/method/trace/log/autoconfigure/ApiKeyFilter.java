package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.security.MtlSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * API Key 鉴权过滤器。
 * <p>
 * 鉴权顺序（命中任一即放行）：
 *  1. X-Api-Key header 与配置值相等（CLI / MCP 友好）
 *  2. MTRACE_SESSION cookie 存在且在 {@link MtlSessionService} 中未过期（浏览器友好）
 *  <p>
 * 关闭鉴权：security.apiKey 为空时一律放行（仅本地/开发）。
 * 公开路径：HTML 页面 panel + 鉴权端点（login / session / logout）始终免密。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String PATH_PREFIX = "/methodTraceLog/";

    /**
     * 免密白名单（精确路径）。HTML 页面 + 鉴权端点本身（login / logout / session/status）。
     * 新增公开资源时在此追加；任何数据/JSON 端点都不要加。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/methodTraceLog/panel",
            "/methodTraceLog/login",
            "/methodTraceLog/logout",
            "/methodTraceLog/session/status"
    );

    private final String configuredKey;
    private final MtlSessionService sessionService;

    public ApiKeyFilter(MethodTraceLogProperties properties, MtlSessionService sessionService) {
        this.configuredKey = properties.getSecurity() == null ? "" : properties.getSecurity().getApiKey();
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 仅在 methodTraceLog 命名空间下生效；其它业务路径完全不受影响
        if (uri == null || !uri.startsWith(PATH_PREFIX)) {
            return true;
        }
        // 白名单内的 HTML 页面 + 鉴权端点本身免密
        return PUBLIC_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 未配置 API Key → 关闭鉴权（仅本地/开发用）
        if (configuredKey == null || configuredKey.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        // CORS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 1. X-Api-Key header
        String provided = request.getHeader(HEADER);
        if (provided != null && provided.equals(configuredKey)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. MTRACE_SESSION cookie（来自 /methodTraceLog/login）
        if (sessionService != null) {
            String sid = readCookie(request, MtlSessionService.COOKIE_NAME);
            if (sessionService.validate(sid)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // 401 + 提示
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Missing or invalid X-Api-Key or session\"}");
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
