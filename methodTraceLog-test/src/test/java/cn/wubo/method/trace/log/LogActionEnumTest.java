package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogActionEnumTest {

    @Test
    void enumValues_shouldExist() {
        // When & Then
        assertNotNull(LogActionEnum.BEFORE);
        assertNotNull(LogActionEnum.AFTER_RETURN);
        assertNotNull(LogActionEnum.AFTER_THROW);
    }

    @Test
    void enumDescriptions_shouldBeCorrect() {
        // When & Then
        assertEquals("方法执行前", LogActionEnum.BEFORE.getDesc());
        assertEquals("方法执行后", LogActionEnum.AFTER_RETURN.getDesc());
        assertEquals("方法抛出异常", LogActionEnum.AFTER_THROW.getDesc());
    }

    @Test
    void enumToString_shouldWork() {
        // When & Then
        assertEquals("LogActionEnum.BEFORE(desc=方法执行前)", LogActionEnum.BEFORE.toString());
        assertEquals("LogActionEnum.AFTER_RETURN(desc=方法执行后)", LogActionEnum.AFTER_RETURN.toString());
        assertEquals("LogActionEnum.AFTER_THROW(desc=方法抛出异常)", LogActionEnum.AFTER_THROW.toString());
    }
}
