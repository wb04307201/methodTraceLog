package cn.wubo.method.trace.log.autoconfigure.otel;

import io.opentelemetry.sdk.trace.IdGenerator;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 自定义 OTel IdGenerator。
 * 当 SimpleOtelServiceImpl 已经把"想要的" spanId hex 推到 SpanIdContext 时，
 * 我们返回它。否则 fallback 到随机（OTel 默认行为）。
 */
public class MtlSpanIdGenerator implements IdGenerator {
    public static final MtlSpanIdGenerator INSTANCE = new MtlSpanIdGenerator();

    @Override
    public String generateTraceId() {
        // traceId 由 setParent(SpanContext) 控制；这里给一个 fallback
        return randomHex(32);
    }

    @Override
    public String generateSpanId() {
        String hex = SpanIdContext.get();
        if (hex != null && hex.length() == 16) {
            return hex;
        }
        return randomHex(16);
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[chars / 2];
        ThreadLocalRandom.current().nextBytes(b);
        StringBuilder sb = new StringBuilder(chars);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }
}
