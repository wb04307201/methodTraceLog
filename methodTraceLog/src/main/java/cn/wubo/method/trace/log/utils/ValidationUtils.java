package cn.wubo.method.trace.log.utils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.util.Set;


public class ValidationUtils {

    private ValidationUtils() {
    }

        /**
     * 验证目标对象是否符合约束条件
     *
     * @param validator 验证器实例，用于执行验证逻辑
     * @param target 待验证的目标对象
     * @param <T> 目标对象的类型
     * @throws ConstraintViolationException 当验证失败时抛出，包含所有违反约束的信息
     */
    public static <T> void validate(Validator validator, T target) {
        // 执行验证并获取违反约束的结果集合
        Set<ConstraintViolation<T>> violations = validator.validate(target);
        // 如果存在违反约束的情况，则抛出异常
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

}
