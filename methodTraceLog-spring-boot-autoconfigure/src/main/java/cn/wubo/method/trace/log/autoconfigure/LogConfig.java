package cn.wubo.method.trace.log.autoconfigure;

import cn.wubo.method.trace.log.CallServiceStrategy;
import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.LogAspect;
import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.ai.CallChain;
import cn.wubo.method.trace.log.ai.TimeComplexity;
import cn.wubo.method.trace.log.impl.log.SimpleLogServiceImpl;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint;
import cn.wubo.method.trace.log.impl.monitor.SimpleMonitorServiceImpl;
import cn.wubo.method.trace.log.utils.DecompilerUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnExpression("${method-trace-log.log.enable:true}")
@EnableConfigurationProperties(MethodTraceLogProperties.class)
@AutoConfigureAfter(name = {
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
        "org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatAutoConfiguration",
        "org.springframework.ai.model.bedrock.autoconfigure.BedrockAiChatAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.elevenlabs.autoconfigure.ElevenLabsChatAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.GoogleGenAiChatAutoConfiguration",
        "org.springframework.ai.model.huggingface.autoconfigure.HuggingFaceChatAutoConfiguration",
        "org.springframework.ai.model.minimax.autoconfigure.MinimaxChatAutoConfiguration",
        "org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration",
        "org.springframework.ai.model.oci.genai.autoconfigure.OciGenAiChatAutoConfiguration",
        "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.stabilityai.autoconfigure.StabilityAiChatAutoConfiguration",
        "org.springframework.ai.model.transformers.autoconfigure.TransformersChatAutoConfiguration",
        "org.springframework.ai.model.vertexai.autoconfigure.VertexAiChatAutoConfiguration",
        "org.springframework.ai.model.zhipuai.autoconfigure.ZhipuAiChatAutoConfiguration",
})
public class LogConfig {

    @Bean
    public SimpleMonitorServiceImpl simpleMonitorService(MeterRegistry meterRegistry) {
        return new SimpleMonitorServiceImpl(meterRegistry);
    }

    @Bean
    public MethodTraceLogEndPoint methodTraceLogEndPoint(MeterRegistry meterRegistry) {
        return new MethodTraceLogEndPoint(meterRegistry);
    }

    @Bean
    public SimpleLogServiceImpl simpleLogService() {
        return new SimpleLogServiceImpl();
    }

    @Bean
    public CallServiceStrategy callServiceStrategy(List<ICallService> callServices, MethodTraceLogProperties properties) {
        return new CallServiceStrategy(callServices, properties);
    }

    @Bean
    public LogAspect logAspect(CallServiceStrategy callServiceStrategy) {
        return new LogAspect(callServiceStrategy);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public TimeComplexity timeComplexity(ChatModel chatModel, MethodTraceLogProperties properties) {
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().build() // logger advisor
                );
        return new TimeComplexity(builder.build(), properties.getTimeComplexity());
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public CallChain callChain(ChatModel chatModel, MethodTraceLogProperties properties) {
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().build() // logger advisor
                );
        return new CallChain(builder.build(), properties.getCallChain());
    }

    @Bean("wb04307201MethodTraceLogAiRouter")
    @ConditionalOnBean(ChatModel.class)
    public RouterFunction<ServerResponse> methodTraceLogAiRouter(CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService, TimeComplexity timeComplexity, CallChain callChain) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/view", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/viewAi.html"))));
        commonRouter(builder, callServiceStrategy, simpleMonitorService);
        builder.GET("/methodTraceLog/view/methodSourceCode", request -> {
                    String className = request.param("className").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "className is required"));
                    String methodName = request.param("methodName").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "methodName is required"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(DecompilerUtils.removeAnnotations(DecompilerUtils.decompile(className, methodName)));
                }
        );
        builder.POST("/methodTraceLog/view/timeComplexity", request -> {
                    Map<String, String> map = request.body(new ParameterizedTypeReference<>() {
                    });
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(timeComplexity.analyze(map.get("sourceCode")));
                }
        );
        builder.POST("/methodTraceLog/view/callChain", request -> {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(callChain.analyze(request.body(String.class)));
                }
        );
        return builder.build();
    }

    @Bean("wb04307201MethodTraceLogRouter")
    @ConditionalOnMissingBean(ChatModel.class)
    public RouterFunction<ServerResponse> methodTraceLogRouter(CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("/methodTraceLog/view", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource(("/view.html"))));
        commonRouter(builder, callServiceStrategy, simpleMonitorService);
        return builder.build();
    }

    private void commonRouter(RouterFunctions.Builder builder, CallServiceStrategy callServiceStrategy, SimpleMonitorServiceImpl simpleMonitorService) {
        builder.GET("/methodTraceLog/view/callServices", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.getCallServices()));
        builder.GET("/methodTraceLog/view/callService", request -> {
                    String name = request.param("name").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
                    Boolean enable = Boolean.valueOf(request.param("enable").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "enable is required")));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(callServiceStrategy.setCallServiceEnable(name, enable));
                }
        );
        builder.GET("/methodTraceLog/view/list", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getMethodTraceInfos()));
        builder.GET("/methodTraceLog/view/traceid", request -> {
                    String id = request.param("id").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(simpleMonitorService.getByTraceId(id));
                }
        );

    }

}
