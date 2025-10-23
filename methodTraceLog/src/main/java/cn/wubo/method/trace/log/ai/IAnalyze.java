package cn.wubo.method.trace.log.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public interface IAnalyze {

    JsonNode analyze(String data) throws JsonProcessingException;

    String call(String data);

    JsonNode trans(String content) throws JsonProcessingException;

}
