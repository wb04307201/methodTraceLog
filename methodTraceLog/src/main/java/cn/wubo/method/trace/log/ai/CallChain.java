package cn.wubo.method.trace.log.ai;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import org.springframework.ai.chat.client.ChatClient;

public class CallChain extends AbstractAnalyze {

    private final ChatClient client;

    private final MethodTraceLogProperties.AiProperties properties;

    public CallChain(ChatClient client, MethodTraceLogProperties.AiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String call(String data) {
        return client.prompt()
                .system(properties.getSystem())
                .user(String.format(properties.getPromptTemplate(), data))
                .call()
                .content();
    }
}
