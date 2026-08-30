package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MethodTraceLogProperties.AlertingProperties} 默认值契约测试。
 * <p>
 * 关键契约：告警默认关闭（opt-in），但一旦打开，阈值 / 窗口 / 冷却都要有可用的非零默认值，
 * 用户不配任何子字段也能直接跑。
 */
class AlertingPropertiesDefaultsTest {

    @Test
    @DisplayName("默认不启用告警，且阈值/窗口/冷却都有可用非零默认值")
    void defaults_disable_alerting() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        // 默认不启用；明确 opt-in 才告警
        Assertions.assertFalse(p.isEnable());
        Assertions.assertTrue(p.getThreshold().getErrorCount() > 0);
        Assertions.assertTrue(p.getThreshold().getWindowSeconds() > 0);
        Assertions.assertTrue(p.getCooldownSeconds() > 0);
    }

    @Test
    @DisplayName("默认 webhookUrl 为空串、classes 为空集合（= 全部类都告警）")
    void defaults_webhook_empty_and_classes_empty() {
        var p = new MethodTraceLogProperties.AlertingProperties();
        Assertions.assertEquals("", p.getWebhookUrl());
        Assertions.assertNotNull(p.getClasses());
        Assertions.assertTrue(p.getClasses().isEmpty());
    }

    @Test
    @DisplayName("MethodTraceLogProperties 默认实例化 alerting 子组")
    void top_level_properties_instantiates_alerting() {
        var top = new MethodTraceLogProperties();
        Assertions.assertNotNull(top.getAlerting());
        Assertions.assertFalse(top.getAlerting().isEnable());
    }
}
