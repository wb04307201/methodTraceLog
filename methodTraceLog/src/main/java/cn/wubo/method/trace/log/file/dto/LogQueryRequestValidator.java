package cn.wubo.method.trace.log.file.dto;

import lombok.experimental.UtilityClass;

import java.time.LocalDateTime;

/**
 * 日志查询请求的运行时业务校验：{@code @NotBlank / @Min / @Max} 已在 Bean Validation 阶段
 * 拦截，这里补的是 @DateTimeFormat 已经反序列化成功之后的语义校验。
 * <p>
 * 由 LogFileConfig 调用，校验失败抛 {@link IllegalArgumentException}，进而被映射为 HTTP 400。
 */
@UtilityClass
public class LogQueryRequestValidator {

    /**
     * 校验日志查询请求。
     * <ul>
     *   <li>没有时间字段 → 不做时间校验</li>
     *   <li>startTime > endTime → 抛 IllegalArgumentException</li>
     *   <li>startTime 远在未来（超过明天） → 抛 IllegalArgumentException</li>
     * </ul>
     *
     * @param req 已通过 @DateTimeFormat 解析的查询请求（startTime / endTime 可能为 null）
     * @throws IllegalArgumentException 当顺序或时间范围非法
     */
    public void validate(LogQueryRequest req) {
        if (req == null) {
            return;
        }
        LocalDateTime startTime = req.getStartTime();
        LocalDateTime endTime = req.getEndTime();
        if (startTime == null && endTime == null) {
            return;
        }
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime must be <= endTime");
        }
        if (startTime != null && startTime.isAfter(LocalDateTime.now().plusDays(1))) {
            throw new IllegalArgumentException("startTime is too far in the future");
        }
    }
}
