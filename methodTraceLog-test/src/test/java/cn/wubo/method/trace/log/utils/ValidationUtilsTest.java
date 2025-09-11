package cn.wubo.method.trace.log.utils;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationUtilsTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validate_validObject_shouldNotThrowException() {
        // Given
        TestValidObject validObject = new TestValidObject();
        validObject.setName("test");
        validObject.setValue(10);

        // When & Then
        assertDoesNotThrow(() -> ValidationUtils.validate(validator, validObject));
    }

    @Test
    void validate_invalidObject_shouldThrowConstraintViolationException() {
        // Given
        TestValidObject invalidObject = new TestValidObject();
        invalidObject.setName(""); // 违反 @NotBlank 约束
        invalidObject.setValue(-1); // 违反 @Min 约束

        // When & Then
        ConstraintViolationException exception = assertThrows(
            ConstraintViolationException.class,
            () -> ValidationUtils.validate(validator, invalidObject)
        );

        assertEquals(2, exception.getConstraintViolations().size());
    }

    @Test
    void validate_partialInvalidObject_shouldThrowConstraintViolationException() {
        // Given
        TestValidObject invalidObject = new TestValidObject();
        invalidObject.setName("validName");
        invalidObject.setValue(-1); // 违反 @Min 约束

        // When & Then
        ConstraintViolationException exception = assertThrows(
            ConstraintViolationException.class,
            () -> ValidationUtils.validate(validator, invalidObject)
        );

        assertEquals(1, exception.getConstraintViolations().size());
    }

    @Data
    private static class TestValidObject {
        @NotBlank
        private String name;

        @Min(0)
        private int value;
    }
}
