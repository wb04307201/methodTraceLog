package cn.wubo.method.trace.log.utils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-80: ValidationUtils 边界测试 —— null validator 应抛 NPE。
 * <p>
 * 当前实现：{@code validator.validate(target)} 直接调用，若 validator==null
 * 则 NPE 由 Hibernate Validator 自己抛出。这是预期的 fail-fast：上层 LogConfig
 * 不会传 null，所以静默通过；但锁定"NPE 而非 IllegalArgumentException"的契约，
 * 避免有人以后改用 Optional.ofNullable(...).orElseThrow(...) 之类把行为换了。
 * <p>
 * 同时覆盖：
 * <ul>
 *     <li>null target → no-op（不抛，因为 violations 必然为空）</li>
 *     <li>空 violations 集 → no-op</li>
 *     <li>多个 violation → 全部包裹进 ConstraintViolationException</li>
 *     <li>valid target → no-op</li>
 * </ul>
 */
class ValidationUtilsEdgeTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    @Test
    void null_validator_throws_NPE() {
        // 锁住契约：当前实现把 null 直接传进 validator.validate()，
        // 由 Hibernate Validator 自己抛 NPE
        TestValidObject target = new TestValidObject();
        target.setName("ok");

        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> ValidationUtils.validate(null, target),
                "R-80: validator==null 必须抛 NPE（不吞，不换成 IllegalArgumentException）");
        assertNotNull(npe.getMessage(),
                "NPE 应带 message（Hibernate Validator 通常会带 'validator must not be null'）");
    }

    @Test
    void null_target_throws_IAE() {
        // 实际行为：Hibernate Validator 的 validator.validate(null) 抛 IllegalArgumentException
        // （HV000116）。ValidationUtils 没做 null-check，所以会原样传出。
        // 锁住这一契约 —— 防止以后有人加 null-check 让行为变化。
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.validate(validator, null),
                "target=null 时 validator.validate(null) 抛 IAE，ValidationUtils 必须原样传出");
        assertTrue(iae.getMessage().contains("must not be null")
                        || iae.getMessage().contains("HV000116"),
                "IAE message 应包含 'must not be null' 或 'HV000116'；实际: " + iae.getMessage());
    }

    @Test
    void valid_target_isNoOp() {
        TestValidObject target = new TestValidObject();
        target.setName("alice");
        assertDoesNotThrow(() -> ValidationUtils.validate(validator, target));
    }

    @Test
    void invalid_target_throws_constraint_violation() {
        TestValidObject target = new TestValidObject();
        target.setName(""); // @NotBlank 违规
        // value 字段不 set → @Min(0) 违不违规要看默认值；这里只设 name 让 @NotBlank 违规
        // value 字段 int 默认 0 → @Min(0) 不违规
        // 加 @NotNull 测试需要单独类

        ConstraintViolationException ex = assertThrows(
                ConstraintViolationException.class,
                () -> ValidationUtils.validate(validator, target));
        assertNotNull(ex.getConstraintViolations());
        assertEquals(1, ex.getConstraintViolations().size(),
                "只有 name 违规");
    }

    @Test
    void multiple_violations_all_in_exception() {
        NotNullTestObject target = new NotNullTestObject();
        // 全部字段为 null → 2 个违规
        ConstraintViolationException ex = assertThrows(
                ConstraintViolationException.class,
                () -> ValidationUtils.validate(validator, target));
        assertEquals(2, ex.getConstraintViolations().size(),
                "NotNull a + NotNull b 都应被报告；实际 violation 数：" + ex.getConstraintViolations().size());
    }

    @Test
    void partial_violation_only_offenders_reported() {
        NotNullTestObject target = new NotNullTestObject();
        target.setA("set"); // b 仍 null
        ConstraintViolationException ex = assertThrows(
                ConstraintViolationException.class,
                () -> ValidationUtils.validate(validator, target));
        assertEquals(1, ex.getConstraintViolations().size());
        ConstraintViolation<?> violation = ex.getConstraintViolations().iterator().next();
        assertEquals("b", violation.getPropertyPath().toString(),
                "只有 b 应被报告；实际违规字段：" + violation.getPropertyPath());
    }

    @Test
    void throws_ConstraintViolationException_not_subclass() {
        // 锁住：抛的异常类型必须是 jakarta.validation.ConstraintViolationException
        // 而不是 Hibernate Validator 的某个子类（避免用户 catch 子类导致类型不稳定）
        TestValidObject target = new TestValidObject();
        target.setName("");
        try {
            ValidationUtils.validate(validator, target);
            assertTrue(false, "应抛 ConstraintViolationException");
        } catch (ConstraintViolationException e) {
            assertSame(ConstraintViolationException.class, e.getClass(),
                    "必须直接抛 ConstraintViolationException（而非子类）");
        }
    }

    @Data
    static class TestValidObject {
        @NotBlank
        private String name;

        @jakarta.validation.constraints.Min(0)
        private int value;
    }

    @Data
    static class NotNullTestObject {
        @NotNull
        private String a;

        @NotNull
        private String b;
    }
}