# Method Trace Log - 方法执行监控和日志

> 该组件可以自动记录方法的执行信息，包括执行时间、参数、返回值等，并提供日志文件监控和可视化功能。


[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)  
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Srping%20Boot-3+-green.svg)

## 功能特性

- **方法调用追踪**：通过AOP切面自动记录方法的调用信息
- **多种记录方式**：
    - 简单日志记录（默认启用，动记录方法执行前、执行后和异常情况，以及调用链追踪）
    - 监控指标记录（基于Micrometer，可选）
- **日志文件管理**：支持日志文件查询和下载
- **实时日志查看**：通过WebSocket提供实时日志监控功能
- **Web界面**：提供简单的Web界面查看日志文件

## 第一步 增加 JitPack 仓库
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

## 第二步 引入jar
```xml
<dependency>
    <groupId>com.gitee.wb04307201.methodTraceLog</groupId>
    <artifactId>methodTraceLog-spring-boot-starter</artifactId>
    <version>1.0.7</version>
</dependency>
```

## 配置文件
添加配置:
```yaml
method-trace-log:
  log:
    enable: true          # 是否启用方法追踪，默认true
    log: true             # 是否启用简单日志记录，默认true
    monitor: true         # 是否启用监控指标记录，默认false
  file:
    enable: true          # 是否启用文件相关功能，默认true
    path: ./logs          # 日志文件路径，默认为项目根目录下的logs文件夹
    allowed-extensions:   # 允许访问的文件扩展名
      - .log
      - .txt
      - .out
    max-lines: 1000       # 单次查询最大行数
    max-file-size: 100    # 文件最大大小（MB）
management:
  endpoints:
    web:
      exposure:
        include: methodtrace
```

## 简单日志记录
启动服务,当访问接口可看到如下输出：
```
2025-08-18T10:59:45.638+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.entity.sql.TestController, methodSignature: public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985638
2025-08-18T10:59:45.644+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.entity.sql.TestService, methodSignature: public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985644
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.entity.sql.TestComponent, methodSignature: public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985647
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.entity.sql.TestComponent, methodSignature: public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985647
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.entity.sql.TestService, methodSignature: public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.entity.sql.TestController, methodSignature: public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
```

traceid 追踪id
pspanid跨度id
spanid父跨度id


## 开启监控指标记录功能和方法调用监控面板
修改配置文件，启用监控指标记录功能，通过Micrometer收集监控指标
```yaml
method-trace-log:
  monitor: true        # 是否启用监控指标记录，默认false
management:
  endpoints:
    web:
      exposure:
        include: methodtrace    # 启用监控的自定义端点
```

通过URL访问内置方法调用监控面板: `http://localhost:8080/log/monitor/view`
![img_1.png](img_1.png)


也可以通过Spring Actuator访问指标数据
访问`/actuator/metrics`，返回类似以下JSON，列出所有可用的指标名称：
访问`/actuator/metrics/<指标名>`获取具体指标数据：

**例如**：
```bash
GET http://localhost:8080/actuator/metrics/method.execution.time
```
```json
{
  "name": "method.execution.time",
  "baseUnit": "seconds",
  "measurements": [
    {
      "statistic": "COUNT",
      "value": 9.0
    },
    {
      "statistic": "TOTAL_TIME",
      "value": 48.1634356
    },
    {
      "statistic": "MAX",
      "value": 0.0
    }
  ],
  "availableTags": [
    {
      "tag": "methodSignature",
      "values": [
        "public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String)",
        "public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String)",
        "public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String)"
      ]
    },
    {
      "tag": "action",
      "values": [
        "AFTER_RETURN"
      ]
    },
    {
      "tag": "className",
      "values": [
        "cn.wubo.entity.sql.TestService",
        "cn.wubo.entity.sql.TestController",
        "cn.wubo.entity.sql.TestComponent"
      ]
    }
  ]
}
```

可以通过自定义端点来查看：
```bash
GET http://localhost:8080/actuator/methodtrace
```
```json
{
  "name": "method.execution.time",
  "baseUnit": "seconds",
  "measurements": [
    {
      "statistic": "COUNT",
      "value": 9.0
    },
    {
      "statistic": "TOTAL_TIME",
      "value": 48.1634356
    },
    {
      "statistic": "MAX",
      "value": 0.0
    }
  ],
  "availableTags": [
    {
      "tag": "methodSignature",
      "values": [
        "public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String)",
        "public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String)",
        "public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String)"
      ]
    },
    {
      "tag": "action",
      "values": [
        "AFTER_RETURN"
      ]
    },
    {
      "tag": "className",
      "values": [
        "cn.wubo.entity.sql.TestService",
        "cn.wubo.entity.sql.TestController",
        "cn.wubo.entity.sql.TestComponent"
      ]
    }
  ]
}
```

## 日志文件管理

通过URL访问日志文件查看器: `http://localhost:8080/log/file/view`
![img.png](img.png)


## 可以继承[ICallService.java](methodTraceLog/src/main/java/cn/wubo/method/trace/log/ICallService.java)接口并实现自定义日志数据据的处理

```java
@Component
@Slf4j
public class CustomLogServiceImpl implements ILogService {

    @Override
    public void log(String traceid, String pspanid, String spanid, String classname, String methodSignature, Object context, LogActionEnum logActionEnum) {
        // 自定义实现
    }
}
```





