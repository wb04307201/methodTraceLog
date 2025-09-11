package cn.wubo.method.trace.log;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@ToString
@AllArgsConstructor
public enum LogActionEnum {

    BEFORE("方法执行前"),

    AFTER_RETURN("方法执行后"),

    AFTER_THROW("方法抛出异常");

    @Getter
    private final String desc;

}
