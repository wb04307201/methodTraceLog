package cn.wubo.method.trace.log.ai;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

public class TimeComplexity {

    private final ChatClient client;

    private final ObjectMapper mapper;

    private final MethodTraceLogProperties.AiProperties properties;

    public TimeComplexity(ChatClient client, MethodTraceLogProperties.AiProperties properties) {
        this.client = client;
        this.properties = properties;
        this.mapper = new ObjectMapper();
    }

    public JsonNode analyze(String code) throws JsonProcessingException {
        String content = client.prompt()
                .system(properties.getSystem())
                .user(String.format(properties.getPromptTemplate(), code))
                .call()
                .content();

        if (content == null) {
            throw new IllegalStateException("AI returned null content");
        }

        if (content.startsWith("<think>")) {
            content = content.substring(content.indexOf("</think>") + 8);
        }

        if(content.contains("```json")){
            content = content.substring(content.indexOf("```json") + 7, content.lastIndexOf("```"));
        }

        return mapper.readTree(content);
    }
}
