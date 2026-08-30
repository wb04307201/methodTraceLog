package cn.wubo.method.trace.log.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link MtlSessionService#create()} 生成的 sessionId 满足“恰好 32 个十六进制字符”的契约。
 *
 * <p>历史背景：原始实现 {@code String.format("%02x", b)} 会触发 Java 对 {@code byte} 的符号扩展，
 * 导致字节 {@code >= 0x80} 被格式化为 8 个字符（{@code "ffffffff"}）而不是 2 个字符（{@code "ff"}）。
 * 这会让 sessionId 长度从 32 变成最多 128。修复方式是 {@code b & 0xff} 屏蔽符号位。</p>
 *
 * <p>本测试不依赖随机运气——16 字节里任一字节落在 {@code [0x80, 0xff]} 就会触发符号扩展。
 * 1000 次调用足以保证命中至少一个高位字节。</p>
 */
class MtlSessionServiceIdFormatTest {

    /** 合法 sessionId 必须是 32 个十六进制字符（小写），对应 128 bit 熵。 */
    private static final String HEX32 = "[0-9a-f]{32}";

    @Test
    void create_returns_exactly_32_hex_chars() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        String sid = svc.create();
        assertNotNull(sid);
        assertEquals(32, sid.length(), "sessionId must be exactly 32 chars, got: " + sid);
        assertTrue(sid.matches(HEX32), "sessionId must be lowercase hex, got: " + sid);
    }

    /**
     * sessionId 长度契约：每个 byte 必须只贡献 2 个十六进制字符（不是 8 个）。
     * <p>
     * 原始实现 {@code String.format("%02x", b)} 的风险点是 byte 在被装箱或提升时丢出
     * 符号位——若不显式 {@code b & 0xff} 屏蔽，落在 {@code [0x80, 0xff]} 范围的字节
     * 会格式化为 {@code "ffffffff"}（8 字符），sessionId 长度因此从 32 涨到最多 128。
     * <p>
     * 修复加上了 {@code b & 0xff} 屏蔽。本测试用 1000 次随机 {@code create()} 兜底：16 个
     * 字节里任意一个落在 {@code [0x80, 0xff]} 就会把"未屏蔽"版本打回原形。统计上 1000 次
     * 调用命中至少一个高位字节的概率 &gt; 1 - (127/256)^(16*1000)，约等于 1。
     */
    @Test
    void byte_sign_extension_is_fixed() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        for (int i = 0; i < 1000; i++) {
            String sid = svc.create();
            assertEquals(32, sid.length(),
                    "iteration " + i + ": sessionId must be exactly 32 chars, got length "
                            + sid.length() + " value=" + sid);
            assertTrue(sid.matches(HEX32),
                    "iteration " + i + ": sessionId must match " + HEX32 + ", got: " + sid);
        }
        assertEquals(1000, svc.size(), "service should hold 1000 live sessions");
    }

    @Test
    void create_produces_unique_ids() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        Set<String> ids = new HashSet<>(1024);
        for (int i = 0; i < 1000; i++) {
            String sid = svc.create();
            assertNotNull(sid);
            assertEquals(32, sid.length(), "iteration " + i + " length: " + sid);
            assertTrue(ids.add(sid), "iteration " + i + " duplicate id: " + sid);
        }
        assertEquals(1000, ids.size());
        assertEquals(1000, svc.size(), "service should hold exactly 1000 live sessions");
    }
}
