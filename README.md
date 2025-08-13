# methodTraceLog

> 方法链路日志，引入依赖，零代码便可拥有针对服务内方法维度的全链路追踪日志

[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)  
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Srping%20Boot-3+-green.svg)

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
    <groupId>com.gitee.wb04307201</groupId>
    <artifactId>methodTraceLog-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

启动服务,当访问接口可看到如下输出：
```
2025-08-13T14:32:54.918+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: null, spanid: 02cf2e2d-897d-47df-8a15-98359f4fbc34, classname: cn.wubo.method.trace.log.TestController, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestController.get(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755066774918
2025-08-13T14:32:54.925+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: 02cf2e2d-897d-47df-8a15-98359f4fbc34, spanid: 6e52c9cc-0d68-4c5b-b1fa-bd4705cb7b85, classname: cn.wubo.method.trace.log.TestService, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestService.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755066774925
2025-08-13T14:32:54.927+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: 6e52c9cc-0d68-4c5b-b1fa-bd4705cb7b85, spanid: c4345138-6ffc-4cdd-9621-3b9c8163b66a, classname: cn.wubo.method.trace.log.TestComponent, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestComponent.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1755066774927
2025-08-13T14:32:54.927+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: 6e52c9cc-0d68-4c5b-b1fa-bd4705cb7b85, spanid: c4345138-6ffc-4cdd-9621-3b9c8163b66a, classname: cn.wubo.method.trace.log.TestComponent, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestComponent.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755066774927
2025-08-13T14:32:54.928+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: 02cf2e2d-897d-47df-8a15-98359f4fbc34, spanid: 6e52c9cc-0d68-4c5b-b1fa-bd4705cb7b85, classname: cn.wubo.method.trace.log.TestService, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestService.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755066774928
2025-08-13T14:32:54.929+08:00  INFO 47312 --- [           main] c.w.m.t.l.s.impl.DefaultLogServiceImpl   : traceid: 0f4ffe12-0cd6-49fb-b8bf-eb20b482bce8, pspanid: null, spanid: 02cf2e2d-897d-47df-8a15-98359f4fbc34, classname: cn.wubo.method.trace.log.TestController, methodSignature: public java.lang.String cn.wubo.method.trace.log.TestController.get(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1755066774929
```

traceid为一次调用链id
pspanid为父级方法id
spanid为当前方法id


## 可以继承[ILogService.java](methodTraceLog/src/main/java/cn/wubo/method/trace/log/service/ILogService.java)接口并实现log自定义切面数据据处理

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

## 可通过配置关闭日志
```yaml
method-trace-log:
    enable: true
```





