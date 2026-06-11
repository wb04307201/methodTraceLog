package cn.wubo.method.trace.log.file;

/**
 * 文件/日志模块内部使用的常量：WebSocket 推送消息的固定字段名等。
 */
public class Constants {

    private Constants() {
    }

    /** WebSocket 推送消息中的错误字段。 */
    public static final String ERROR = "error";

    /** WebSocket 推送消息中的文本消息字段。 */
    public static final String MESSAGE = "message";

}
