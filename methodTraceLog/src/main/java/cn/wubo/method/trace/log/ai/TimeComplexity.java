package cn.wubo.method.trace.log.ai;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.springframework.ai.chat.client.ChatClient;

public class TimeComplexity extends AbstractAnalyze {

    private final ChatClient client;

    private final MethodTraceLogProperties.AiProperties properties;

    public TimeComplexity(ChatClient client, MethodTraceLogProperties.AiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String call(String code) {
        return client.prompt()
                .system(properties.getSystem())
                .user(String.format(properties.getPromptTemplate(), code))
                .call()
                .content();
    }
}
