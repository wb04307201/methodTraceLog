# Method Trace Log - 方法观测日志

<div align="right">
  <a href="README.md">English</a> | 中文
</div>

> Spring Boot starter，提供方法调用链路追踪、性能监控、日志文件管理、CFR 反编译。附带独立 MCP 服务（`methodTraceLog-mcp`），通过 stdio 把上述能力开放给 AI Agent。

[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Spring%20Boot-3+-green.svg)

![gif.gif](gif.gif)

---

## 功能

| | |
|---|---|
| **方法追踪** | AOP 拦截，全链路 `traceid` / `spanid` / `pspanid`；可采样；`@AspectLog` 可改名 |
| **指标** | 每个方法的 Micrometer `Timer` + 父子 `MethodTraceInfo` 树；通过 `actuator/methodtrace` 暴露 |
| **日志文件** | 读 / 过滤 / 下载；WebSocket 实时 tail；路径穿越保护 |
| **CFR 反编译** | HTTP 端点，支持应用类 / 第三方 jar / fat-jar 嵌套，自动去除注解 |
| **OTel 导出** | classpath 上有 `opentelemetry-sdk` 时自动桥接到 OTLP/HTTP |
| **W3C traceparent** | HTTP 入站和 `RestClient` 出站自动注入/提取 |
| **Cookie 会话** | 浏览器 `POST /methodTraceLog/login` 登录；CLI / MCP 继续用 `X-Api-Key` |
| **MCP 服务** | 独立 stdio 进程；15 个工具，多主机，通过 HTTP 转发到目标应用 |

---

## 快速开始

### 引入依赖

```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>methodTraceLog-spring-boot-starter</artifactId>
    <version>1.0.20</version>
</dependency>
```

### 最小配置

```yaml
method-trace-log:
  log:
    enable: true
  file:
    enable: true
    path: ./logs
  security:
    api-key: change-me-in-production   # 生产环境必填；留空则关闭鉴权（仅限开发）

management:
  endpoints:
    web:
      exposure:
        include: methodtrace           # 面板依赖的端点
```

### 打开面板

`http://localhost:8080/methodTraceLog/panel` —— 单页 4 个 tab：概览 / 调用记录（含 JSON/CSV 导出）/ 日志文件（含实时 tail）/ 反编译（CFR）。

---

## 配置参考

```yaml
method-trace-log:
  log:
    enable: true                                  # AOP 总开关
    sample-rate: 1.0                              # 0.0 ~ 1.0；子调用继承父决定（启动时会自动 clamp）
    exclude-patterns: []                          # 方法名黑名单（大小写不敏感精确匹配）；命中后直接 proceed()，不发任何事件。例如 [equals, hashCode, toString, canEqual]
    service-calls:                                # 启动时各服务开关
      - { name: CustomLog,         enable: false }   # 内置: SimpleLogService / SimpleMonitorService / AlertingService / CustomLog
    trace-store:                                  # 内存 trace 树持久化
      type: in-memory                             # in-memory | file | none
      path: ./trace-store                         # 仅 type=file 时生效（自动按 yyyy-MM-dd 建子目录）
      max-traces: 1000                            # recent map 上限（in-memory / file 的内存缓存共用）
      ttl-millis: 28800000                        # 8h，clean() 会删超过此时长的磁盘文件
      rebuild-index-on-start: false               # 仅 type=file 时生效；启动时扫描目录重建 traceId→file 索引，文件量大时拖慢启动
  file:
    enable: true
    path: ./logs
    allowed-extensions: [.log, .txt, .out]
    scan-lines: 10000                             # 流式扫描行数上限；Files.lines() + limit() 是懒加载，文件本身多大都无所谓，没有 size check
    max-file-size: 100MB                          # 单文件大小上限（默认；0/null = 不限制）
    total-size-cap: 10GB                          # 目录下所有滚动文件总大小上限
    # log-pattern: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[([^\]]+)\]\s+(\w+)\s+([^\s]+)\s*-\s*(.*)
  security:
    api-key: change-me-in-production              # 留空 = 关闭鉴权
    session:
      ttl-millis: 28800000                        # 浏览器 cookie 会话 8h 滑动过期
    cors:                                         # 跨域配置（面板部署在不同 origin 时使用；opt-in：空列表 = 不注册）
      allowed-origins: []                         # ["https://your-panel.example.com"]；["*"] = 全部（与 credentials 互斥）
      allowed-methods: [GET, POST, OPTIONS, DELETE, PUT]
      allowed-headers: [Content-Type, X-Api-Key, Authorization]
      allow-credentials: false                    # true 时 allowed-origins 不能是 "*"
      max-age: 0                                  # preflight 缓存秒数（0 = 每次重新校验）
  decompile:
    timeout-seconds: 10                           # CFR daemon 线程超时
  otel:                                           # 需要 classpath 上有 opentelemetry-sdk
    enable: false
    endpoint: http://localhost:4318/v1/traces
    service-name: method-trace-log
    service-namespace: ""                        # Resource service.namespace 标签
    export-delay-millis: 5000                    # OTLP 客户端内置 batching 延迟
    max-queue-size: 2048
    max-export-batch-size: 512
    export-timeout-millis: 30000
  propagate:                                      # W3C traceparent 传播
    http-inbound: true                            # TraceContextFilter 读 traceparent
    rest-client-outbound: true                    # RestClient.Builder 拦截器
    rest-template-interceptor: true               # 暴露 RestTemplate 拦截器 Bean
  alerting:                                       # 异常告警（opt-in，默认关闭）
    enable: false                                 # true 时注册 AlertingService 作为 ICallService
    webhook-url: ""                               # 空 = 只本地记录；非空则异步 POST 事件（best-effort）
    threshold:
      error-count: 10                             # 同一 class#method 在窗口内错误数达到该值触发
      window-seconds: 60                          # 滑动窗口长度
    cooldown-seconds: 300                         # 同一 key 在冷却期内不再重复告警
    classes: []                                   # 白名单（前缀匹配）；空 = 全部类都告警
```

### 更新日志

Round 7–18 的关键改动（12 个 round、约 38 个 commit），上一次文档同步以来新增的内容——完整记录见 `TEST_REPORT.md` §Round 7 之后。

- **覆盖率**（Round 7）— 14 个集成测试类（`TracePropagationIT`、`OtelPropagationIT`、`AlertingIT`、`SlowMethodIT`、`SamplingIT`、`ExcludePatternIT`、`TraceStoreIT`、`LogFileQueryIT`、`LogFileMonitorIT`、`DecompileIT`、`SessionAuthIT`、`CorsIT`、`PanelIT`、`McpIntegrationIT`）双路径验证（JUnit HTTP + Agent MCP）覆盖全部能力。`cn.wubo.method.trace.log.e2e.*` 包下新增 26 个 e2e + 11 个深度覆盖测试方法 = 37 个新测试。Round 11 又补了 7 个 IT 类（5 层以上嵌套调用、并发 trace 隔离、MDC 清理、cooldown 抑制、sample-rate 排除、内存 OTel 导出、文件存储持久化）。Round 7 期间同时发现并修复了 2 个真实 bug：`CorsFilterConfig` 自 Round 5 起就漏挂在 `AutoConfiguration.imports` 上（生产环境 CORS 一直是坏的）；`exclude-patterns` 代码里加了但测试 app 的 YAML 一直没写。

- **跨实例 `pspanid` 修复**（Round 8–9）— 协调两个文件的改动，解决 Round 7 抓到的 MDC 键名错位（Ruling 6）。`LogAspect` 改成 `pspanid = prespanid != null ? prespanid : prepspanid`：进程内嵌套调用用调用方 span id，跨实例入站调用用 W3C `traceparent` 头上游的 parent span id。`SimpleMonitorServiceImpl.save()` 把判定从 `pspanid == null` 换成 `methodTraceInfoMap.get(pspanid) == null`（空安全）——真正的根调用和跨实例入站都存为顶层条目；进程内的子节点只挂载不存。

- **OTel 测试环境**（Round 10）— `OtelPropagationIT` body 现在真正端到端执行。测试 harness 启动 primary context 时把 `method-trace-log.otel.enable=true` 注入，并把 SDK bean 装进 `GlobalOpenTelemetry`，这样 `Span.current().getSpanContext().isValid()` 返回 true，跨 JVM traceid 断言才有意义。`@AfterAll` 用 `GlobalOpenTelemetry.resetForTest()` 清理。从 1 skip 变成 0 skip。

- **风险驱动的测试**（Round 12、16–17）— 2026-08-29 风险清单上的 92 条风险系统性地全部关闭：Round 12 新增 14 个测试类 / 53 个方法（Top 风险），Round 16 新增 8 个类 / 74 个方法（中等），Round 17 新增 13 个类 / 97 个方法（低级）。覆盖边界、属性、并发竞争、淘汰竞争、生命周期 / 关闭路径、资源泄漏、边缘场景等。`cn.wubo.method.trace.log.sampler.SampledDecision` 删除（Round 17 R-66，grep 确认生产代码零引用）。

- **`/code-review` 修复**（Round 13）— code-review 跑出来 9 个真实 bug 全部修复：
  - `CorsFilterConfig` 注册的是 `/*` 而不是 `/methodTraceLog/*`，静默覆盖了用户为其他 URL 模式定义的过滤器。
  - `ErrorMessagePropertiesPostProcessor` 用了 `addFirst` 加到 `PropertySources` 列表上，把 `application.yml` / 命令行上的用户值盖掉了；改成 `addLast`，starter 默认值优先级最低。
  - `AlertingService` 的 cooldown 是 check-then-put，非原子，并发场景下会重复发 webhook；改用 `putIfAbsent(key, now)`。
  - `DecompilerUtils.extractMethod` 正则不识别构造方法（没有返回类型组）；现在构造方法也能匹配。
  - `/methodTraceLog/decompile` 在方法无法提取时（如构造方法）返回 404，而不是文档约定的整类源码 fallback；恢复原契约；外部消费者要拿 404 信号的就得自己适配。
  - `SimpleMonitorServiceImpl` 跨线程竞争，导致父的 `BEFORE` 在子的 `BEFORE` 之后到达时会重复插入根；改成检测到父有 in-flight 子节点就跳过 save。
  - `sample-rate` clamp 在 YAML 写 `NaN`/`Infinity` 时会抛；`Double.isFinite(rate) ? clamp : 1.0` 修复。
  - 日志路径的 env-post-processor 在目录不可写时静默成功；改成 `Files.isWritable` 探测后抛 `IllegalArgumentException`（落实文档的 fail-fast）。
  - `AlertingService` 之前用 `transContext(info.getContext())` 把原始 `Throwable` 丢掉了；改成 `info.getRawException()`，原始异常 + stack trace 都能保留。

- **MCP 模块加固**（Round 14–15）— 18 个修复。亮点：
  - **HTTP 超时**：3s 连接，普通调用 30s 读 / 长轮询 120s 读。先用 `JdkClientHttpRequestFactory`，后来切到 Apache HttpClient 5（`@Bean(destroyMethod="close") CloseableHttpClient`），保证 context 关闭时连接池能释放（`spring.lifecycle.timeout-per-shutdown-phase=30s`）。
  - **16 MiB 响应大小上限**通过 `SizeLimitingClientHttpRequestFactory`（带 `Content-Length` 预检 + 流式 `BoundedInputStream` 字节计数）。Spring 6.2 的 `RestClient.Builder` 没有 `ExchangeStrategies.maxInMemorySize`，只能写一个委托 factory。
  - **幂等 GET 重试** 指数退避（2 次重试，100–500ms），通过 `ToolOp` 枚举 + `doWithRetry`。
  - **结构化错误 JSON** 通过 `safeGet`/`safePost` + `toErrorJson` + cause-chain walker —— 永不泄漏 stack trace。
  - **多主机配置校验**：`@PostConstruct validateHosts()` 拒绝空 `hosts[]` / 重名 / 空字段 / 非 http(s) URL；cleartext `http://` + 非空 `api-key` 输出 WARN。
  - **`ping` 韧性**：先试 `/actuator/health`，fallback 到 `/methodTraceLog/view/callServices`，都 404 时返回 `HOST_NOT_EXPOSING_ACTUATOR` 信封。
  - **URL 编码修复**：`URLEncoder.encode(s, UTF_8).replace("+", "%20")` 走 RFC 3986。
  - **参数校验**：`getMethodTraceByTraceId` 校验 `^[A-Za-z0-9_-]{1,128}$`；`validateLogQueryArgs` 把 `fileName ≤ 256`、`keyword ≤ 1024`、ISO 8601 起止时间都校验；应用到 `queryLogContent`、`downloadLog`、`startMonitor`、`stopMonitor`。
  - **结构化审计日志**通过 `mcp.audit` SLF4J logger；`doWithRetry` 每调用输出一行 `tool= host= path= status= duration=ms`（`logback-spring.xml` 路由到 `System.err`，stdin 上的 JSON-RPC 保持干净）。
  - **+71 个 MCP 测试**（13 → 46 → 84）。

- **性能**（Round 18）— `LogFileService.queryLogs` 把 `page<1` 的运行时 guard 上移到方法顶部，赶在任何 `Files.lines().limit(maxScanLines)` 读+过滤之前；同步加了 `pageSize<1` guard。非法输入现在快速失败，不再付文件 IO 代价；原来的读后 page guard 保留作为兜底。

**Round 18 收尾后的测试总数**：**565 个测试**（主模块 475 + MCP 模块 90），全绿，0 skip。


---

## HTTP 端点

`security.api-key` 非空时，除 `/methodTraceLog/panel`（HTML 页面本身）外，所有路由都需要 `X-Api-Key` 或 `MTRACE_SESSION` cookie。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/methodTraceLog/panel` | HTML 面板 — 4 tab（白名单） |
| GET | `/methodTraceLog/view/callServices` | 服务列表 + 当前 enable |
| GET | `/methodTraceLog/view/callService?name=&enable=` | 运行时开关 |
| GET | `/methodTraceLog/view/list?className=&methodName=&onlyErrors=&limit=` | 最近根 trace |
| GET | `/methodTraceLog/view/traceid?id=` | 单个 trace 完整调用链 |
| GET | `/methodTraceLog/view/export?format=json\|csv&className=&methodName=&onlyErrors=&limit=` | 批量导出（默认 limit 1000） |
| GET | `/methodTraceLog/view/alerts?limit=` | 最近告警事件（默认 limit 50；告警未启用时返回空 list） |
| GET | `/methodTraceLog/view/slowMethods?windowMinutes=&topN=` | Micrometer histogram 出来的最慢方法 top-N（默认 5min / top 10） |
| GET | `/methodTraceLog/decompile?className=&methodName=&timeoutSeconds=` | text/plain 源码 |
| GET | `/methodTraceLog/logFile/files` | 日志目录文件列表 |
| POST | `/methodTraceLog/logFile/query` | 关键字 / 时间 / 级别过滤分页 |
| POST | `/methodTraceLog/logFile/download` | 流式下载 |
| GET | `/methodTraceLog/logFile/monitor/{start,stop,status}?fileName=` | REST 实时 tail（STOMP 之外） |
| POST | `/methodTraceLog/login` | Body `{"apiKey":"..."}` → `Set-Cookie: MTRACE_SESSION=...` |
| POST | `/methodTraceLog/logout` | 销毁会话 |
| GET | `/methodTraceLog/session/status` | `{ sessionValid: true/false }` |
| WS | `/ws` (SockJS) → `/topic/log-monitor` | 实时日志推送；STOMP 发送到 `/app/{start-monitor,stop-monitor,monitor-status,heartbeat}` |
| GET | `/actuator/methodtrace` | 每个方法 / 每个类的 Micrometer 统计 |

---

## MDC 中的 trace id

`LogAspect` 把 `traceid` / `spanid` / `pspanid` 写入 SLF4J MDC。把它放进 logback pattern 就能让日志自带链路信息：

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] [trace=%X{traceid} span=%X{spanid}] %-5level %logger - %msg%n</pattern>
```

跨服务时 `TraceContextFilter` 解析入站 `traceparent`，`RestClient` 拦截器给所有出站请求注入 `traceparent` —— 上下游 `traceid` 自动接上。

---

## Web 面板鉴权

`security.api-key` 非空时,面板用页面顶部的**横幅**(不是弹窗)收集 API Key:

- 首次访问 `/methodTraceLog/panel` 且没有有效 session 时,横幅出现在 tab 内容上方。
- 提交正确的 Key(点按钮或按 **Enter**)后,横幅消失、`MTRACE_SESSION` cookie 被设置,当前 tab 的数据**自动加载**——无需手动刷新。
- 错误 / 空 Key 会显示行内错误(`请输入 API Key` / `❌ API Key 无效或鉴权未启用`)。
- Session 是 **8 小时滑动 cookie**(`security.session.ttl-millis`,默认 28 800 000)。每次使用都会续期,所以 8 小时不操作或点 **🚪 注销** 之前都不用重输。
- header 上的登出按钮只在有 session 时才出现;`/methodTraceLog/logout` 销毁 cookie 后,横幅会自动重新挂载。
- 开发模式(`api-key: ""`)下横幅完全不显示,也不会有登出按钮。

`GET /methodTraceLog/session/status` 返回 `{ authEnabled, sessionValid }`,面板 JS 据此决定是否显示横幅。横幅挂着时,页面内的 `mtlFetch` 会把 401 暂存到队列,登录成功后自动重放,登录过程中不会出现 "unauthorized" 提示。

CLI / MCP 客户端继续用 `X-Api-Key` 请求头即可,cookie session 只是为了浏览器体验更顺。

---

## `@AspectLog` 注解

方法级独立开关（不要求所在类是 `@Component`）。用来给 trace 列表和 OTel span 起个可读名：

```java
public class MyHelper {
    @AspectLog("do-something")
    public void doSomething(String s) { ... }
}
```

trace 列表和 OTel span name 会显示 `do-something` 而不是原始方法签名。

---

## 自定义 `ICallService`

继承 `AbstractCallService` 实现 `consumer(ServiceCallInfo)`，由 `CallServiceStrategy` 自动发现：

```java
@Component
public class MyService extends AbstractCallService {

    @Override
    public void consumer(ServiceCallInfo info) {
        // logActionEnum: BEFORE / AFTER_RETURN / AFTER_THROW
        log.info("{} {}", info.getClassName(), info.getMethodName());
    }

    @Override public String getCallServiceName() { return "MyService"; }
    @Override public String getCallServiceDesc() { return "我的服务"; }
}
```

启动时关闭：`service-calls: [{ name: MyService, enable: false }]`，再用面板开。

---

## MCP 服务

独立 Spring Boot 进程，stdio 通信，把 `@Tool` 调用通过 HTTP 转发到一个或多个目标主机（每个目标 = 部署了 starter 的应用）。

Claude Desktop / Cursor / Cline 等 AI 客户端的 MCP server 配置（`mcpServers` JSON 块）：

```json
{
  "mcpServers": {
    "methodTraceLog-mcp": {
      "command": "jbang.cmd",
      "args": [
        "io.github.wb04307201:methodTraceLog-mcp:1.0.20",
        "--method-trace-log.mcp.hosts[0].name=local-dev",
        "--method-trace-log.mcp.hosts[0].url=http://localhost:8080",
        "--method-trace-log.mcp.hosts[0].description=本地开发",
        "--method-trace-log.mcp.hosts[0].api-key=change-me-in-production"
      ]
    }
  }
}
```

**15 个工具**：`getHosts`, `ping`, `getCallServices`, `setCallServiceEnable`, `getMethodTraceList`, `getMethodTraceByTraceId`, `getAlerts`, `getSlowMethods`, `decompileMethod`, `getLogFiles`, `queryLogContent`, `downloadLog`, `startMonitor`, `stopMonitor`, `getMonitorStatus`。

---

## 使用注意

- **LogAspect 排除规则**：框架内部类型（`ICallService` / `MethodTraceLogEndPoint` / `LogFileService` / `LogFileRealTimeService`）默认不追踪。如果有类似内部 bean 不想被追踪，加入 `LogAspect` 的 pointcut 表达式。
- **路径穿越保护**：`FileUtils.pathInspection` 是 `LogFileService` 和 `LogFileRealTimeService` 共用的白名单 —— `[a-zA-Z0-9._-]+`，不允许 `..`，长度 ≤ 255。
- **日志 pattern**：默认 `log-pattern` 只匹配标准 logback pattern。如果改了 `logback.xml` 里的 `<pattern>`，要同步更新 `log-pattern`，否则 `LogLineInfo.parse` 不会拆分关键字 / 级别 / 时间。
- **CFR 资源**：通过 ClassLoader 的 `getResourceAsStream` 读 class 字节 —— 文件路径、thin jar、Spring Boot fat-jar 嵌套 jar 都通用。不要去解析 `URL.getPath()` 字符串。
- **Spring Boot fat-jar**：测试模块没有声明 `spring-boot-maven-plugin`，所以 `mvn package` 出来的 jar 没主清单。运行方式：`mvn package` 之后 `java -cp target/classes;<classpath> cn.wubo.method.trace.log.MethodTraceLogTestApplication`。
- **Maven**：本机 `mvn` 命令是坏的，调用 `/c/developer/apache-maven-3.9.16/bin/mvn`。
