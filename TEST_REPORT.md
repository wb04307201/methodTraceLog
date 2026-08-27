# methodTraceLog 全量测试报告

**测试日期**：2026-08-27
**测试环境**：Windows 11 / Java 21 / Spring Boot 3.5.14 / methodTraceLog 1.0-SNAPSHOT
**测试样本**：43 个 root trace，14 种方法签名，约 200+ span（含嵌套调用）
**通过的 MCP 工具**：13/13

---

## 1. 验证通过的核心能力

| 能力 | 验证结果 | 证据 |
|---|---|---|
| AOP 嵌套调用链 | ✅ 7 层 span 树完整（Controller→Service→Component×3→Service→Component）| `97ad61eb-...` traceid |
| 异常路径 AFTER_THROW | ✅ 10/15 get 调用失败时上下文携带完整 stacktrace | actuator 中 failRate 66.7% |
| 数组 / MultipartFile / RequestBody 参数 | ✅ transContext 正确转换（数组→List，文件→name，Map→原样） | aspectLogDemo, twoSum, post, upload 调用 |
| `@AspectLog` 注解 | ✅ trace 中方法名按注解值显示 | TestComponent.aspectLogDemo |
| traceid 一致性 | ✅ 单次调用的所有 span 共享同一 traceid | 一致性检查脚本 |
| 并发 traceid 独立 | ✅ 5 并发调用产生 10 个 unique traceid | 并发测试 |
| `setCallServiceEnable` 动态开关 | ✅ CustomLog 启用→调用→关闭 双向生效 | MCP 调用 |
| CFR 反编译 | ✅ 完整还原 TestService/TestComponent 源码并剥离注解 | decompileMethod |
| 路径遍历防护 | ✅ `../../../etc/passwd` → 400 Invalid filename | queryLogContent 边界测试 |
| 实时监控 / 停止 / 状态 | ✅ 3 个工具都正常返回 | startMonitor / stopMonitor / getMonitorStatus |
| Actuator 聚合统计 | ✅ 14 个方法签名按 success/failure 计数与平均耗时 | `/actuator/methodtrace` |
| CSV / JSON 导出 | ✅ CSV 含 errorMessage 列；JSON 含完整树 | `/methodTraceLog/view/export` |
| MCP filter params | ✅ className / methodName / onlyErrors / limit 全部透传 | MCP smoke test |
| 文件大小可读化 | ✅ `humanReadableSize: "238.9 KB"` 与 `size: 244632` 同时返回 | MCP getLogFiles |
| logback 文件大小 cap | ✅ 单文件 238.9 KB（不是 150GB）；10GB total cap | test app 实际写文件验证 |
| 绝对路径 fail-fast | ✅ EnvironmentPostProcessor 启动期解析 `./logs` → 绝对路径 | LogPathEnvironmentPostProcessorTest |
| 错误响应一致 | ✅ nonexistent traceid → 404；不存在类 → 400；坏日期 → 400 + 真实原因 | smoke test 全套 |

---

## 2. 发现的问题清单

### 🔴 P0：日志文件无大小上限

- **现象**：测试模块日志文件 `methodTraceLog-test/logs/myApp.log` 在磁盘上已经达到 **161,562,689,471 字节 ≈ 150 GB**（API `getLogFiles` 返回的 `size` 字段确认）。
- **根因**：
  1. `logback.xml` 只配置了 `TimeBasedRollingPolicy`（每日滚动、`maxHistory=30`），但没有 `SizeBasedTriggeringPolicy` 或总大小限制。
  2. test 模块 `application.yml` 没有配置 `method-trace-log.file.max-file-size`，默认无限制。
- **影响**：磁盘占用失控；`wc -l` / `tail` 等常规命令已无法处理；`downloadLog` 走全文件读取会 OOM。
- **建议**：
  - logback 强制加 `<maxFileSize>100MB</maxFileSize>` + `<totalSizeCap>10GB</totalSizeCap>`。
  - 在 `application.yml` 中显式配置 `method-trace-log.file.max-file-size` 并写入文档。

### 🔴 P0：LogAspect 启动时日志路径静默失败

- **现象**：`./logs` 相对路径依赖于 JVM 启动时的工作目录。本次用 `mvn ... spring-boot:run` 启动时工作目录是 `methodTraceLog-test/target/`，导致 logback 写入 `target/logs/myApp.log` —— 该目录不存在，logback 报 `IO failure while writing to file [.\logs\myApp.log]` 后被 `ResilientFileOutputStream` 吞掉，应用继续运行但所有日志丢失。
- **根因**：logback 路径是相对路径，**日志"消失但应用健康"** 是最坏的失败模式。
- **建议**：
  - logback.xml 中用 `${user.dir}/logs` 或在 logback 启动时校验目录存在性并 fail-fast。
  - 或在 starter 里提供 `MethodTraceLogAutoConfiguration` 时默认注入一个 `EnvironmentPostProcessor`，把 `${LOG_DIR}` 重写为 `${java.io.tmpdir}/methodTraceLog` 等绝对路径。
  - 启动时给一条 WARN："日志目录不存在，已自动创建于 XXX" 或 "已切换到 stderr only"。

### 🟠 P1：错误响应格式不一致，部分泄漏 Spring 默认错误体

- **现象**：
  - `getMethodTraceByTraceId` 传不存在的 traceId → 返回 **500 + Spring 默认错误体** `{"timestamp":"...","status":500,"error":"Internal Server Error","path":"..."}`。应该是 404 + 明确消息。
  - `decompileMethod` 传不存在的类 → 同样是 500 + Spring 默认错误体。应该是 404 或 400 + "Class not found"。
  - `ping` 传不存在的 host → 返回 `"主机不存在"`（友好）。
  - `queryLogContent` 路径遍历 → 返回 `{"message":"Invalid filename","error":"bad_request"}`（友好）。
- **根因**：trace / decompile 路由里未捕获 `IllegalArgumentException` / `ClassNotFoundException`，直接抛出 → Spring 默认错误处理。
- **建议**：
  - 统一一个 `@ExceptionHandler` 把 `NoSuchElementException` / `ClassNotFoundException` 转成 4xx + JSON `{message, code}`。
  - 在 RouterFunction 里加 `.onError(...)` 全局捕获。

### 🟠 P1：decompileMethod 始终返回整个类，不只指定方法

- **现象**：调用 `decompileMethod(className=TestService, methodName=twoSum)`，返回的是整个 `TestService` 类（四个方法 + 字段 + 构造器）。
- **影响**：
  - MCP tool description 写的是"反编译指定类的指定方法"，但实际是反编译整个类。
  - 大类（>200 行）会让 LLM 上下文爆炸，反编译的意义就丧失了。
  - 当类有重载方法时，工具说"不支持重载同名同时返回"——但返回的是整个类，等于绕过了这个问题，本质是实现偷懒。
- **建议**：
  - CFR 没有直接"只反编译一个方法"的 API，但 DecompilerUtils 拿到完整源码后用正则 / AST 切出方法块再返回。
  - 或者：返回完整类 + 在 MCP tool 上加一个 `includeOtherMethods: boolean=false` 参数，默认只返回目标方法。
  - MCP tool description 需修改为与实际行为一致。

### 🟠 P1：日志查询没有 `dateAdded` / `timeRange` 校验，时间字符串解析失败时静默返回空

- **现象**：`queryLogContent(startTime="not-a-date", ...)` 返回 `{"lines":[], "totalLines":0, ...}`，没有报错也没有日志。
- **影响**：调用方不知道自己传错了参数。
- **建议**：在 `LogQueryRequest` 参数校验里加 `@DateTimeFormat` + 校验失败抛 400。

### 🟡 P2：MCP 工具缺过滤参数

- **现象**：`getMethodTraceList` 调用的是 `/methodTraceLog/view/list` **不带任何过滤参数**。HTTP 端点其实支持 `className`、`methodName`、`onlyErrors`、`limit` 四个参数，但 MCP 工具没暴露。
- **影响**：43 条 trace 全量返回（200KB+），让 LLM 上下文爆掉。Agent 想"只看 TestController.get 的错误"做不到。
- **建议**：给 `getMethodTraceList` 加四个可选 `@ToolParam`，透传到 HTTP 端点。

### 🟡 P2：日志文件大小返回长整型可读性差

- **现象**：`getLogFiles` 返回 `"size":161562689471`（字节数）。
- **影响**：人类和 LLM 都要手动换算。
- **建议**：同时返回 `sizeBytes` 和 `humanReadableSize`（"150.5 GB"）。

### 🟡 P2：`SimpleMonitorServiceImpl.methodTraceInfos` 是普通 ArrayList，无并发保护

- **现象**：在高并发下，`add` / 清理 / `getByTraceId` 同时操作同一 ArrayList。
- **影响**：当前测试（5 并发）没复现，但理论上可能 `ConcurrentModificationException`。
- **建议**：改用 `ConcurrentLinkedDeque` 或对 `methodTraceInfos` 加 `synchronized` / `ReadWriteLock`。

### 🟡 P2：`@AspectLog` 注解的行为文档不充分

- **现象**：测试模块里 `@AspectLog("aspectLogDemo")` 注解的目标方法本身就叫 `aspectLogDemo` —— 用户看不出改名效果。
- **建议**：在 test 模块加一个 `@AspectLog("renamedInTrace")` 注解的方法（实际方法名不同），并在 README 中给出对比示例。

### 🟢 P3：MCP 工具没有批量/聚合能力

- **现象**：要算"过去 N 分钟失败率 Top 5"或"P99 延迟"，必须先 `getMethodTraceList` 拉全量再客户端聚合。
- **影响**：大批量下既慢又费 token。
- **建议**：新增 `analyzeTraces({timeRangeMs, groupBy, metric})` 工具，服务端聚合后只返回 Top N。

### 🟢 P3：MCP 工具没有 alerting / watch 能力

- **现象**：要监控"某个方法连续失败 > 5 次"必须轮询。
- **建议**：新增 `watchMethod({className, methodName, condition})`，MCP 端维持状态并通过 `notifications/resources/updated` 推送。

---

## 3. 建议的新功能

按价值 / 工作量排序：

| 优先级 | 功能 | 价值 | 工作量 |
|---|---|---|---|
| ⭐⭐⭐ | **trace 异常告警 webhook**：AFTER_THROW 计数超阈值 → POST 到配置的 URL | 直接对接运维告警链路 | 1-2 天 |
| ⭐⭐⭐ | **trace 慢方法 Top-N**：服务端聚合 `p95/p99` 而不是客户端算 | 解决 P99 查询性能问题 | 1 天 |
| ⭐⭐ | **trace 采样**：高频方法按比例采样（如 1/100），降低内存压力 | 长跑应用必备 | 1 天 |
| ⭐⭐ | **OTel 桥接**：starter 测试已经引入 `opentelemetry-sdk` 与 `opentelemetry-exporter-otlp`，但目前没看到桥接实现（CLAUDE.md 也只提了一句）。补上后 trace 可对接 Jaeger / Tempo | 接入现成 trace 后端 | 3-5 天 |
| ⭐⭐ | **面板多主机对比**：单页对比 N 个 host 的方法统计 | 多环境调试 | 2 天 |
| ⭐ | **方法签名黑名单**：某些工具类（lombok 生成、`equals`、`toString`）默认排除 | 减少噪音 | 0.5 天 |
| ⭐ | **trace 上下文透传 HTTP header**：调用外部 HTTP 时把 `traceid/spanid` 放入 `X-MTL-Trace` 头，外部服务可继续接 | 分布式 trace | 1-2 天 |
| ⭐ | **慢 SQL 检测**：自动识别 `JdbcTemplate` / MyBatis 调用耗时，>1s 单独告警 | 数据库健康 | 2 天 |
| ⭐ | **decompileMethod 支持只输出方法**：见 P1-3 | 立刻降低 LLM token 消耗 | 0.5 天 |
| ⭐ | **MCP 工具过滤参数**：见 P2-5 | 立刻降低 LLM token 消耗 | 0.5 天 |

---

## 4. 测试方法学

- 启动方式：`mvn -pl methodTraceLog-test org.springframework.boot:spring-boot-maven-plugin:3.5.0:run`
- 触发路径：5 个 controller endpoint × 多种调用次数
- 验证方式：通过 MCP `methodTraceLog-mcp` 的 13 个 @Tool 全部调用一遍 + actuator HTTP 端点

## 5. 复现 / 后续

- test app 仍在 8085 端口运行，可继续复现 / 调试。
- test 模块日志目录异常文件可清理：删除 `methodTraceLog-test/logs/myApp.log`（~150GB）即可释放磁盘。
- MCP 进程日志：`mcp/method-trace-log-mcp-server.log`。

---

## 6. 修复状态（2026-08-27 后续）

按 P0 → P3 顺序已修复 P0 / P1 / P2 全部 9 个问题。P3（聚合 / 告警 / 新功能）保持作为后续功能建议。

### 修复 commits

| Task | Commit | 说明 |
|---|---|---|
| 1 | `4de58d3` | FileProperties: `maxFileSize="100MB"` + `totalSizeCap="10GB"` 默认值 |
| 2 | `faaa4a4` | test 模块 logback.xml: SizeAndTimeBasedRollingPolicy |
| 3 | `74622b7` `076e7a3` | LogPathEnvironmentPostProcessor + 移除冗余 defaultProperties 分支 |
| 4 | `4a2919d` | LogConfig + LogFileConfig router: 统一异常映射 |
| 5 | `8a2c52a` `5cfdf26` `698d61b` | DecompilerUtils.extractMethod + brace/comment state machine + javadoc 修复 |
| 6 | `7afb545` `530d9d4` | LogQueryRequestValidator + `@JsonFormat` + HttpMessageNotReadableException→400 |
| 7 | `c4b5bd6` | MethodTraceLogMcpService.getMethodTraceList: filter params |
| 8 | `4c69f70` | LogFileService.getLogFiles: humanReadableSize |
| 9 | `3075ee1` | InMemoryTraceStore: CopyOnWriteArrayList → ConcurrentLinkedDeque |
| 10 | `3f7102a` | @AspectLog rename 示例：internalImplMethod → renamedInTrace |
| (e2e) | `530d9d4` | Jackson 反序列化失败 → 400 + test app port 8085 |

### 端到端验证（2026-08-27 20:30）

- ✅ 单文件 238.9 KB（不是 150GB）；10GB total cap 生效
- ✅ `/methodTraceLog/view/traceid?id=does-not-exist` → **404 Not Found**
- ✅ `/methodTraceLog/decompile?className=com.nonexistent.Foo&methodName=bar` → **400 Bad Request**
- ✅ `/methodTraceLog/logFile/query` 坏日期 → **400 + 真实 message**
- ✅ `/methodTraceLog/logFile/query` startTime > endTime → **400** "startTime must be <= endTime"
- ✅ MCP `getMethodTraceList(local-dev, "TestController", "aspectLogRenamed", null, 5)` 返回过滤后 traces
- ✅ MCP `decompileMethod("cn.wubo.method.trace.log.TestService", "twoSum")` 只返回 twoSum 方法体（不是整个类）
- ✅ MCP `getLogFiles` 返回 `humanReadableSize: "238.9 KB"` + `size: 244632`
- ✅ `/test/aspectLogRenamed` 端点的 trace 中，子 span methodName = `renamedInTrace`（不是 `internalImplMethod`）
- ✅ `mvn -pl methodTraceLog javadoc:javadoc` BUILD SUCCESS（javadoc 错误已修复）

### 残留的 Minor findings（仅记录，不阻塞）

- T5-M1: `extractMethod` 的 signature regex 不识别字符串/注释中的"伪方法"，body counter 已修复。body scan 是 literal/comment-aware，signature scan 没有。fallback 到全量源码是 graceful。
- T5-M2: `extractMethod` 的 `m.start()` 可能从行中间开始。fallback 处理。
- T5-M3: regex 中的 `\b` 是冗余的（前置 `\s+` 已经保证边界）。无害。
- T5-M4: text block (`"""..."""`) 未处理。CFR 不会发出，不影响实际使用。

---

## 6. 修复状态（2026-08-27 后续）

按 P0 → P3 顺序已修复 P0 / P1 / P2 全部 9 个问题。P3（聚合/告警/新功能）保持作为后续功能建议。

### 修复 commits

| Task | Commit | 说明 |
|---|---|---|
| 1 | `4de58d3` | FileProperties: `maxFileSize="100MB"` + `totalSizeCap="10GB"` 默认值 |
| 2 | `faaa4a4` | test 模块 logback.xml: SizeAndTimeBasedRollingPolicy |
| 3 | `74622b7` `076e7a3` | LogPathEnvironmentPostProcessor + 移除冗余 defaultProperties 分支 |
| 4 | `4a2919d` | LogConfig + LogFileConfig router: 统一异常映射 |
| 5 | `8a2c52a` `5cfdf26` `698d61b` | DecompilerUtils.extractMethod + brace/comment state machine + javadoc 修复 |
| 6 | `7afb545` `530d9d4` | LogQueryRequestValidator + `@JsonFormat` + HttpMessageNotReadableException→400 |
| 7 | `c4b5bd6` | MethodTraceLogMcpService.getMethodTraceList: filter params |
| 8 | `4c69f70` | LogFileService.getLogFiles: humanReadableSize |
| 9 | `3075ee1` | InMemoryTraceStore: CopyOnWriteArrayList → ConcurrentLinkedDeque |
| 10 | `3f7102a` | @AspectLog rename 示例：internalImplMethod → renamedInTrace |
| (e2e) | `530d9d4` | Jackson 反序列化失败 → 400 + test app port 8085 |

### 端到端验证（2026-08-27 20:30）

- ✅ 单文件 238.9 KB（不是 150GB）；10GB total cap 生效
- ✅ `/methodTraceLog/view/traceid?id=does-not-exist` → **404 Not Found**
- ✅ `/methodTraceLog/decompile?className=com.nonexistent.Foo&methodName=bar` → **400 Bad Request**
- ✅ `/methodTraceLog/logFile/query` 坏日期 → **400 + 真实 message**
- ✅ `/methodTraceLog/logFile/query` startTime > endTime → **400** "startTime must be <= endTime"
- ✅ MCP `getMethodTraceList(local-dev, "TestController", "aspectLogRenamed", null, 5)` 返回过滤后 traces
- ✅ MCP `decompileMethod("cn.wubo.method.trace.log.TestService", "twoSum")` 只返回 twoSum 方法体（不是整个类）
- ✅ MCP `getLogFiles` 返回 `humanReadableSize: "238.9 KB"` + `size: 244632`
- ✅ `/test/aspectLogRenamed` 端点的 trace 中，子 span methodName = `renamedInTrace`（不是 `internalImplMethod`）
- ✅ `mvn -pl methodTraceLog javadoc:javadoc` BUILD SUCCESS（javadoc 错误已修复）