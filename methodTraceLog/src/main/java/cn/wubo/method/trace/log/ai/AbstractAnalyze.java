package cn.wubo.method.trace.log.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class AbstractAnalyze<T> implements IAnalyze<T> {

    protected static ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);

    @Override
    public JsonNode analyze(T data) throws JsonProcessingException {
        String content = call(data);
        return trans(content);
    }

    @Override
    public JsonNode trans(String content) throws JsonProcessingException {
        if (content == null) {
            throw new IllegalStateException("AI returned null content");
        }

        if (content.startsWith("<think>")) {
            content = content.substring(content.indexOf("</think>") + 8);
        }

        if (content.contains("```json")) {
            content = content.substring(content.indexOf("```json") + 7, content.lastIndexOf("```"));
        }

        return mapper.readTree(content);
    }
}
