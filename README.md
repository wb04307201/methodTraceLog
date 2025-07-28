# rate-limit-spring-boot-starter

[![](https://jitpack.io/v/com.gitee.wb04307201/log-spring-boot-starter.svg)](https://jitpack.io/#com.gitee.wb04307201/log-spring-boot-starter)
[![star](https://gitee.com/wb04307201/log-spring-boot-starter/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/log-spring-boot-starter)
[![fork](https://gitee.com/wb04307201/log-spring-boot-starter/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/log-spring-boot-starter)
[![star](https://img.shields.io/github/stars/wb04307201/log-spring-boot-starter)](https://github.com/wb04307201/log-spring-boot-starter)
[![fork](https://img.shields.io/github/forks/wb04307201/log-spring-boot-starter)](https://github.com/wb04307201/log-spring-boot-starter)  
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Srping%20Boot-3+-green.svg)

> 一个注解@EnableLog搞定日志
> 日志内容包含服务内调用链，类名，方法签名，方法参数，方法返回值，方法执行时间，方法执行结果，方法执行错误信息等

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
    <artifactId>log-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 第三步 在启动类上加上`@EnableLog`注解
```java
@EnableLog
@SpringBootApplication
public class LogDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimitDemoApplication.class, args);
    }

}
```

启动服务访问接口可看到如下输出：
```
2025-07-28T17:17:00.926+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: null, spanid: be4616ce-4186-4960-8e9c-cd6179923faf, classname: TestController, methodSignature: public java.lang.String TestController.get(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1753694220926
2025-07-28T17:17:00.931+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: be4616ce-4186-4960-8e9c-cd6179923faf, spanid: ac4c50d2-0c57-47a5-b44c-08cf721fa0bf, classname: TestService, methodSignature: public java.lang.String TestService.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1753694220931
2025-07-28T17:17:00.934+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: ac4c50d2-0c57-47a5-b44c-08cf721fa0bf, spanid: c5e96410-864c-413b-b818-fcb731bbd357, classname: TestComponent, methodSignature: public java.lang.String TestComponent.hello(java.lang.String), context: [java], logActionEnum: LogActionEnum.BEFORE(desc=方法执行前), time: 1753694220934
2025-07-28T17:17:00.935+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: ac4c50d2-0c57-47a5-b44c-08cf721fa0bf, spanid: c5e96410-864c-413b-b818-fcb731bbd357, classname: TestComponent, methodSignature: public java.lang.String TestComponent.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1753694220935
2025-07-28T17:17:00.935+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: be4616ce-4186-4960-8e9c-cd6179923faf, spanid: ac4c50d2-0c57-47a5-b44c-08cf721fa0bf, classname: TestService, methodSignature: public java.lang.String TestService.hello(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1753694220935
2025-07-28T17:17:00.935+08:00  INFO 56168 --- [           main] cn.wubo.log.core.SimpleeLogServiceImpl   : traceid: e3264e8a-8cc2-4905-a5eb-5cbabb66d143, pspanid: null, spanid: be4616ce-4186-4960-8e9c-cd6179923faf, classname: TestController, methodSignature: public java.lang.String TestController.get(java.lang.String), context: JAVA say:'hello world!', logActionEnum: LogActionEnum.AFTER_RETURN(desc=方法执行后), time: 1753694220935
```

traceid为一次调用链id
pspanid为父级方法id
spanid为当前方法id


## 可以继承接口[ILogService.java](src/main/java/cn/wubo/log/core/ILogService.java)并实现log自定义切面数据据处理，修改配置对应到自定义实现类

```yml
log:
  logService: cn.wubo.log.core.SimpleeLogServiceImpl //修改成自定义实现类
```





