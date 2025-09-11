package cn.wubo.method.trace.log.file.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LogQueryRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validation_validRequest_shouldPass() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        request.setFileName("test.log");
        request.setPage(1);
        request.setPageSize(50);

        // When
        Set<ConstraintViolation<LogQueryRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @Test
    void validation_emptyFileName_shouldFail() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        request.setFileName("");
        request.setPage(1);
        request.setPageSize(50);

        // When
        Set<ConstraintViolation<LogQueryRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertEquals(1, violations.size());
        assertEquals("文件名不能为空", violations.iterator().next().getMessage());
    }

    @Test
    void validation_invalidPage_shouldFail() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        request.setFileName("test.log");
        request.setPage(0); // 违反 @Min(1) 约束
        request.setPageSize(50);

        // When
        Set<ConstraintViolation<LogQueryRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("页码必须大于0"));
    }

    @Test
    void validation_invalidPageSize_shouldFail() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        request.setFileName("test.log");
        request.setPage(1);
        request.setPageSize(0); // 违反 @Min(1) 约束

        // When
        Set<ConstraintViolation<LogQueryRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("每页行数必须大于0"));
    }

    @Test
    void validation_pageSizeExceedsMax_shouldFail() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        request.setFileName("test.log");
        request.setPage(1);
        request.setPageSize(1001); // 违反 @Max(1000) 约束

        // When
        Set<ConstraintViolation<LogQueryRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("每页行数不能超过1000"));
    }

    @Test
    void gettersAndSetters_shouldWorkCorrectly() {
        // Given
        LogQueryRequest request = new LogQueryRequest();
        LocalDateTime startTime = LocalDateTime.now().minusDays(1);
        LocalDateTime endTime = LocalDateTime.now();

        // When
        request.setFileName("test.log");
        request.setPage(3);
        request.setPageSize(200);
        request.setKeyword("error");
        request.setLevel("ERROR");
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        request.setReverse(false);

        // Then
        assertEquals("test.log", request.getFileName());
        assertEquals(3, request.getPage());
        assertEquals(200, request.getPageSize());
        assertEquals("error", request.getKeyword());
        assertEquals("ERROR", request.getLevel());
        assertEquals(startTime, request.getStartTime());
        assertEquals(endTime, request.getEndTime());
        assertFalse(request.isReverse());
    }

    @Test
    void defaultValues_shouldBeSetCorrectly() {
        // Given
        LogQueryRequest request = new LogQueryRequest();

        // Then
        assertEquals(1, request.getPage());
        assertEquals(100, request.getPageSize());
        assertTrue(request.isReverse());
        assertNull(request.getKeyword());
        assertNull(request.getLevel());
        assertNull(request.getStartTime());
        assertNull(request.getEndTime());
    }
}
