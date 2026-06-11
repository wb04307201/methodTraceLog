package cn.wubo.method.trace.log.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MtlSessionServiceTest {

    @Test
    void create_returnsNonEmptyHex() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        String sid = svc.create();
        assertNotNull(sid);
        assertEquals(32, sid.length());
        assertTrue(sid.matches("[0-9a-f]{32}"));
    }

    @Test
    void create_eachTimeUnique() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        String s1 = svc.create();
        String s2 = svc.create();
        assertNotEquals(s1, s2);
        assertEquals(2, svc.size());
    }

    @Test
    void validate_existing_returnsTrue_andSlidesExpiry() throws Exception {
        MtlSessionService svc = new MtlSessionService(60_000L);
        String sid = svc.create();
        // 即便不睡眠，validate 应当成功
        assertTrue(svc.validate(sid));
        // 多次 validate 仍然成功
        assertTrue(svc.validate(sid));
        assertTrue(svc.validate(sid));
    }

    @Test
    void validate_unknown_returnsFalse() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        assertFalse(svc.validate("nonexistent"));
    }

    @Test
    void validate_nullOrEmpty_returnsFalse() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        assertFalse(svc.validate(null));
        assertFalse(svc.validate(""));
    }

    @Test
    void validate_expired_returnsFalse() throws Exception {
        // ttl 设为 200ms，等 400ms 后必过期
        MtlSessionService svc = new MtlSessionService(200L);
        String sid = svc.create();
        Thread.sleep(400);
        assertFalse(svc.validate(sid));
        assertEquals(0, svc.size());
    }

    @Test
    void invalidate_removesSession() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        String sid = svc.create();
        assertTrue(svc.validate(sid));
        svc.invalidate(sid);
        assertFalse(svc.validate(sid));
        assertEquals(0, svc.size());
    }

    @Test
    void invalidate_nullSafe() {
        MtlSessionService svc = new MtlSessionService(60_000L);
        svc.invalidate(null); // 不抛
        svc.invalidate("never-existed"); // 不抛
    }

    @Test
    void cookieName_isStable() {
        assertEquals("MTRACE_SESSION", MtlSessionService.COOKIE_NAME);
    }
}
