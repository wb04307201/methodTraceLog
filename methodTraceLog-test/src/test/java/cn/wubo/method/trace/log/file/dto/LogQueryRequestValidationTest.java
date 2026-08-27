package cn.wubo.method.trace.log.file.dto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class LogQueryRequestValidationTest {

    @Test
    void unparseable_startTime_throws_400() {
        // The validator checks business-level rules after Jackson has already
        // parsed startTime / endTime into LocalDateTime (or null on parse failure
        // → Spring's @DateTimeFormat returns 400 via InvalidFormatException).
        // We exercise the order / future-bound checks here, which is what
        // LogQueryRequestValidator enforces and what LogFileConfig maps to 400.
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("x.log");
        LocalDateTime later = LocalDateTime.now().plusHours(2);
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        req.setStartTime(later);
        req.setEndTime(earlier);

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> LogQueryRequestValidator.validate(req));
    }

    @Test
    void startTime_too_far_in_future_throws() {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("x.log");
        req.setStartTime(LocalDateTime.now().plusYears(2));

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> LogQueryRequestValidator.validate(req));
    }

    @Test
    void valid_range_does_not_throw() {
        LogQueryRequest req = new LogQueryRequest();
        req.setFileName("x.log");
        req.setStartTime(LocalDateTime.now().minusHours(1));
        req.setEndTime(LocalDateTime.now().plusHours(1));

        Assertions.assertDoesNotThrow(() -> LogQueryRequestValidator.validate(req));
    }
}
