# methodTraceLog 方法调用追踪和监控

> 一个基于Spring AOP和Micrometer的Java方法调用追踪和监控工具

[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)  
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Srping%20Boot-3+-green.svg)

## 功能特性

- **全链路追踪**: 通过MDC（Mapped Diagnostic Context）实现调用链追踪
- **自动日志记录**: 自动记录方法执行前、执行后和异常情况
- **唯一标识**: 为每次请求生成唯一的traceId和spanId
- **灵活配置**: 支持自定义日志服务实现
- **类型安全**: 使用枚举定义日志动作类型
- **监控**: 可选Micrometer收集方法调用的监控指标

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
    <version>1.0.5</version>
</dependency>
```
默认使用使用DefaultLogServiceImpl进行基础日志记录
启动服务,当访问接口可看到如下输出：
```
2025-08-18T10:59:45.638+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.entity.sql.TestController, methodSignature: public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985638
2025-08-18T10:59:45.644+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.entity.sql.TestService, methodSignature: public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985644
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.entity.sql.TestComponent, methodSignature: public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755485985647
2025-08-18T10:59:45.647+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, spanid: 4c1ba448-612b-463a-8f75-a3eb6262e37f, classname: cn.wubo.entity.sql.TestComponent, methodSignature: public java.lang.String cn.wubo.entity.sql.TestComponent.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985647
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: a52a7934-88d3-44e9-bcf5-1469a0364493, spanid: e9526f48-e423-4112-a9e2-8b3843c0d15a, classname: cn.wubo.entity.sql.TestService, methodSignature: public java.lang.String cn.wubo.entity.sql.TestService.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
2025-08-18T10:59:45.648+08:00  INFO 17236 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 734415a6-6059-42c9-95ee-399dd4877aab, pspanid: null, spanid: a52a7934-88d3-44e9-bcf5-1469a0364493, classname: cn.wubo.entity.sql.TestController, methodSignature: public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755485985648
```

traceid为一次调用链id
pspanid为父级方法id
spanid为当前方法id


## 可通过配置开启监控指标
使用MonitorLogServiceImpl进行日志记录和性能监控
```yaml
method-trace-log:
  monitor: true
```
访问`/actuator/metrics`，返回类似以下JSON，列出所有可用的指标名称：
访问`/actuator/metrics/<指标名>`获取具体指标数据：
**指标名**；
- method.calls.total: 方法调用总数 
- method.success.total: 方法成功执行次数 
- method.exceptions.total: 方法异常次数 
- method.execution.time: 方法执行时间分布

**例如**：
```bash
GET http://localhost:8080/actuator/metrics/method.calls.total?tag=className:cn.wubo.entity.sql.TestController
```
```json
{
  "name": "method.calls.total",
  "measurements": [
    {
      "statistic": "COUNT",
      "value": 2.0
    }
  ],
  "availableTags": [
    {
      "tag": "methodSignature",
      "values": [
        "public java.lang.String cn.wubo.entity.sql.TestController.get(java.lang.String)"
      ]
    }
  ]
}
```
更多请查看Spring Boot Actuator中端点的访问规则以及相关配置

## 可以继承[ILogService.java](methodTraceLog/src/main/java/cn/wubo/method/trace/log/service/ILogService.java)接口并实现自定义日志数据据的处理

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
**注意**：自定义会使默认的日志和监控功能失效

## 生产环境可通过配置关闭日志功能
```yaml
method-trace-log:
    enable: false
```





