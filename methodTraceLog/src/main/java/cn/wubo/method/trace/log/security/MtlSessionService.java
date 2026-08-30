package cn.wubo.method.trace.log.security;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MtlSession 服务端会话管理。
 * <p>
 * 浏览器客户端通过 {@code POST /methodTraceLog/login} 把 X-Api-Key 换成不透明 sessionId，
 * 之后用 cookie 自动鉴权。CLI / MCP 仍可继续用 X-Api-Key header。
 * <p>
 * 设计：
 *  - 不存 key，只存 sessionId → 过期时间
 *  - sessionId 128 bit 随机，SecureRandom
 *  - 默认 8 小时过期，cookie 是 HttpOnly + SameSite=Lax
 *  - 内存存储，重启即清空（key 轮换时旧会话自动失效，无需服务端主动清）
 *  - 后台线程每 5 分钟清理一次过期条目
 */
@Slf4j
public class MtlSessionService {

    /** 浏览器 cookie 名。 */
    public static final String COOKIE_NAME = "MTRACE_SESSION";

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long ttlMillis;

    /**
     * 构造方法。
     *
     * @param ttlMillis 会话有效期（毫秒），小于等于 0 会被钳到 1ms
     */
    public MtlSessionService(long ttlMillis) {
        // 允许任意 >0 的 TTL；设置过小仅会让 session 几乎立刻过期，不会破坏不变量。
        this.ttlMillis = Math.max(1L, ttlMillis);
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtl-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 创建会话，返回 sessionId。
     *
     * @return 32 字符十六进制的随机 sessionId（128 bit 熵）
     */
    public String create() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        String sid = sb.toString();
        sessions.put(sid, System.currentTimeMillis() + ttlMillis);
        return sid;
    }

    /**
     * 验证 sessionId 是否存在且未过期。验证通过时**续期**。
     *
     * @param sessionId 客户端发来的 sessionId
     * @return true 表示有效（同时会把过期时间滑动到 now+ttlMillis）
     */
    public boolean validate(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Long expiry = sessions.get(sessionId);
        if (expiry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > expiry) {
            sessions.remove(sessionId);
            return false;
        }
        // 滑动过期：每次访问续期
        sessions.put(sessionId, now + ttlMillis);
        return true;
    }

    /**
     * 主动注销。
     *
     * @param sessionId 要销毁的 sessionId；null 时 no-op
     */
    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /**
     * 当前内存中持有的 session 数量。
     *
     * @return session 条数（含已过期但尚未被清理线程回收的）
     */
    public int size() {
        return sessions.size();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("mtl-session: cleaned {} expired sessions", removed);
        }
    }
}
