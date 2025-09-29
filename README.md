# Method Trace Log - 方法追踪日志

> 一个用于方法追踪日志的starter组件，提供方法调用链路追踪、性能监控、日志文件管理和时间复杂度分析等功能。

[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)  
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Srping%20Boot-3+-green.svg)

---

![gif.gif](gif.gif)

## 功能特性

### 方法追踪
- 自动记录方法调用链路
- 支持方法执行时间统计
- 可视化展示调用关系和耗时
- 支持异常捕获和记录

### 日志文件管理
- 实时查看日志文件内容
- 支持日志文件下载
- 日志内容搜索和过滤
- WebSocket实时日志推送

### AI代码分析（可选）
- 时间复杂度分析
- 性能优化建议
- 代码质量评估
- 可视化分析结果展示

---

## 安装使用

### 增加 JitPack 仓库
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### Maven依赖
```xml
<dependency>
    <groupId>com.gitee.wb04307201.methodTraceLog</groupId>
    <artifactId>methodTraceLog-spring-boot-starter</artifactId>
    <version>1.0.14</version>
</dependency>
```

### 配置文件
添加配置:
```yaml
method-trace-log:
  log:
    enable: true          # 是否启用方法追踪，默认true
    serviceCalls:        # 启动时便开启的日志服务，默认无需配置全部开启，生产环境可以配置全部关闭，在需要时可通过web界面开启
      - name: SimpleLogService  # 日志输出服务
        enable: false
      - name: SimpleMonitorService  # 指标监控服务
        enable: false
  file:
    enable: true          # 是否启用文件相关功能，默认true
    path: ./logs          # 日志文件路径
    allowed-extensions:   # 允许访问的文件扩展名
      - .log
      - .txt
      - .out
    max-lines: 1000       # 单次查询最大行数
    max-file-size: 100    # 文件最大大小（MB）
    # 日志文件匹配模式, 
    # 默认(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[([^\]]+)\]\s+(\w+)\s+([^\s]+)\s*-\s*(.*)
    # 匹配%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n日志输出格式
    log-pattern: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[([^\]]+)\]\s+(\w+)\s+([^\s]+)\s*-\s*(.*)
management:
  endpoints:
    web:
      exposure:
        include: methodtrace # 开启自定义端点
```

### AI分析配置（可选）

如果需要使用AI代码分析功能，需要引入和配置Spring AI，下面以通过ollama调用qwen3为例：
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
</dependencies>
```

```yaml
spring:
  ai:
    ollama:
      chat:
        options:
          model: qwen3    # 使用的模型
      base-url: http://localhost:11434
method-trace-log:
  ai:
    # 系统提示语，有默认值 
    system: 你是一个专业的代码时间复杂度分析专家。请严格按照JSON格式返回分析结果，不要包含任何其他文本。
    # 提示语模板，有默认值
    promptTemplate: |
      请分析以下java代码的时间复杂度，并返回严格的JSON格式结果：

            代码：
            ```java
            %s
            ```

            分析模式：快速模式（基于启发式规则）

            请返回以下JSON格式的分析结果（不要包含任何其他文本）：

            {
              "overallComplexity": "整体时间复杂度（如O(n²)）",
              "confidence": 分析置信度（0-100的数字）,
              "explanation": "详细的复杂度分析说明",
              "lineAnalysis": [
                {
                  "lineNumber": 行号,
                  "complexity": "该行的时间复杂度",
                  "explanation": "该行复杂度的详细解释",
                  "code": "该行的代码内容"
                }
              ],
              "suggestions": [
                {
                  "type": "优化类型（space-time-tradeoff/algorithm-refactor/data-structure/loop-optimization）",
                  "title": "优化建议标题",
                  "description": "详细的优化建议描述",
                  "codeExample": "优化后的示例代码",
                  "impact": "影响程度（high/medium/low）"
                }
              ],
              "visualData": {
                "chartData": [
                  {"inputSize": 10, "operations": 100, "complexity": "O(n²)"},
                  {"inputSize": 100, "operations": 10000, "complexity": "O(n²)"},
                  {"inputSize": 1000, "operations": 1000000, "complexity": "O(n²)"}
                ],
                "complexityBreakdown": [
                  {"section": "循环部分", "complexity": "O(n²)", "percentage": 80, "color": "#ef4444"},
                  {"section": "初始化部分", "complexity": "O(1)", "percentage": 20, "color": "#22c55e"}
                ]
              }
            }

            请确保：
            1. 分析所有重要的代码行，特别是循环、递归和函数调用
            2. 提供具体的优化建议和示例代码
            3. 生成合理的可视化数据
            4. 置信度要基于代码的复杂程度和分析的准确性
            5. 返回的JSON必须是有效的格式，不包含注释或其他文本`
```

## 使用

### 默认输出方的法日志：
```
2025-08-18T10:59:45.638+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.method.trace.log.TestController, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestController.get(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985638
2025-08-18T10:59:45.644+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.method.trace.log.TestService, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestService.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985644
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.method.trace.log.TestComponent, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestComponent.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985647
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.method.trace.log.TestComponent, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestComponent.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985647
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.method.trace.log.TestService, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestService.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.method.trace.log.TestController, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestController.get(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
```

追踪id - traceid
跨度id - spanid
父跨度id - pspanid
通过traceid，spanid，pspanid可以追踪调用链


### 使用监控面板和Actuator集成
项目集成了Spring Boot Actuator，可以通过以下自定义端点查看监控信息：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: methodtrace
```

**注意**：*配置开启methodtrace使用监控面板的全部功能*

通过URL访问内置方法调用监控面板: `http://localhost:8080/methodTraceLog/view`
![img.png](img.png)
![img_1.png](img_1.png)
如果配置了AI分析功能。则可以分析方法的时间复杂度以及优化建议
![img_2.png](img_2.png)
![img_3.png](img_3.png)
![img_4.png](img_4.png)
![img_5.png](img_5.png)


### 使用日志文件管理

通过URL访问日志文件查看器: `http://localhost:8080/methodTraceLog/logFile`
![img_6.png](img_6.png)


### 可以继承[AbstractCallService.java](methodTraceLog/src/main/java/cn/wubo/method/trace/log/AbstractCallService.java)接口并实现自定义日志数据据的处理

```java
@Slf4j
public class CustomLogServiceImpl extends AbstractCallService {

    public static final String LOG_TEMPLATE = "custom-log traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}";

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        if (serviceCallInfo.getLogActionEnum() == LogActionEnum.AFTER_THROW)
            log.error(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
        else
            log.info(LOG_TEMPLATE, serviceCallInfo.getTraceid(), serviceCallInfo.getPspanid(), serviceCallInfo.getSpanid(), serviceCallInfo.getClassName(), serviceCallInfo.getMethodSignature(), transContext(serviceCallInfo.getContext()), serviceCallInfo.getLogActionEnum(), serviceCallInfo.getTimeMillis());
    }

    @Override
    public String getCallServiceName() {
        return "CustomLog";
    }

    @Override
    public String getCallServiceDesc() {
        return "自定义日志";
    }
}
```





