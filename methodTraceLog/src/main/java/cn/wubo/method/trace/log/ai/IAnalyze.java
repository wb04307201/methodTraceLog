package cn.wubo.method.trace.log.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public interface IAnalyze<T> {

    JsonNode analyze(T data) throws JsonProcessingException;

    String call(T data);

    JsonNode trans(String content) throws JsonProcessingException;

}
