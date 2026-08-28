# Full-Coverage E2E Test Plan — methodTraceLog

> **Spec for:** end-to-end coverage of every feature in `methodTraceLog`, verified via dual path (JUnit HTTP + Agent MCP).
> **Path:** architectural (new test infrastructure across two modules).
> **Author:** brainstorming 2026-08-29.

## Goal

为 `methodTraceLog` 项目产出**覆盖全部功能的端到端测试**，验证链路如下：

1. **JUnit 集成测试**：在 `methodTraceLog-test/.../e2e/` 下加 14 个 `*IT` 类，对真实启动的 host app 走 HTTP（TestRestTemplate / RestClient）
2. **Agent MCP 验证**：本会话内由 AI Agent 通过 `methodTraceLog-mcp` 的 15 个 `@Tool` 直接以 MCP 协议验证同一组场景
3. **双路径互证**：JUnit 断言与 MCP 响应一一对照，任一路径发现差异即记录

**不在范围**：单元测试（已 35 个测试类覆盖）、性能压测、Spring Boot 升级兼容性、多协议（gRPC/Kafka）trace 透传。

## Architecture

### 覆盖矩阵

**Core（深度集成，多实例 + 错误路径）**

| 特性 | MCP 工具 | host 端点 | 关键场景 |
|---|---|---|---|
| Trace 传播 | `getMethodTraceByTraceId`, `getMethodTraceList` | `/test/callRemote`, `/test/callRemoteRestTemplate`, `/test/aspectLog` | 双实例；RestClient + RestTemplate 双链路；traceid 一致；父子 span 嵌套 |
| OTel 传播 | （无直接工具，但需间接验证） | `/test/otel-out?port=` | internal `traceid` ≡ OTel `Span.current().traceId` |
| Alerting | `getAlerts` | `/test/aspectLogRenamedThrow`, `/test/throw` | 阈值触发 / 冷却 / 类白名单 / 4xx 响应 |
| Slow Method | `getSlowMethods` | `/test/slow?sleepMs=` | p95/p99 排序、topN 边界、窗口时长 |
| Sampler | `getMethodTraceList` | `/test/sampled?iterations=` | `sampleRate=0/1/0.5` 边界，验证进入 `/view/list` 的比例 |
| Exclude Patterns | `getMethodTraceList` | 已存在 `/test/blacklist` | Lombok `@Data` 生成方法（equals/hashCode/toString）不出现在 trace |
| Trace Store | `getMethodTraceList` | actuator `methodtrace` | 切换 in-memory / file / none 后行为差异 |

**Peripheral（冒烟）**

| 特性 | MCP 工具 | host 端点 |
|---|---|---|
| Log File Query | `getLogFiles`, `queryLogContent`, `downloadLog` | `/methodTraceLog/logFile/{files,query,download}` |
| Log File Monitor | `startMonitor`, `stopMonitor`, `getMonitorStatus` | `/methodTraceLog/logFile/monitor/{start,stop,status}` |
| Decompile | `decompileMethod` | `/methodTraceLog/decompile` |
| Session Auth | （无 MCP 工具） | `/methodTraceLog/{login,logout,session/status}` |
| CORS | （无 MCP 工具） | OPTIONS preflight against any `/methodTraceLog/*` |
| Panel | （无 MCP 工具） | `GET /methodTraceLog/panel` |
| Hosts / Ping | `getHosts`, `ping` | — |

### Harness 类

`cn.wubo.method.trace.log.e2e.MtlE2eHarness`（`AutoCloseable`）：

| 方法 | 用途 |
|---|---|
| `static MtlE2eHarness primary(int port, Map<String,Object> props)` | 启动 host app（默认 8085） |
| `static MtlE2eHarness secondary(int port)` | 启动第二个实例（用于跨实例传播） |
| `TestRestTemplate http()` | 带 `X-Api-Key: change-me-in-production` 头的 client |
| `int port()` | 当前实例端口 |
| `MethodTraceInfo awaitTrace(String traceid, Duration timeout)` | 轮询 `/view/traceid?id=…` 直到出现 |
| `List<MethodTraceInfo> awaitTraceList(int minCount, Duration timeout)` | 轮询 `/view/list` 直到计数达标 |
| `List<Map<String,Object>> awaitWebhook(int minCount, Duration timeout)` | 轮询 `/_test/echo-webhook` 直到 webhook 收到 |
| `void clearWebhook()` | DELETE `/_test/echo-webhook` |
| `Optional<MethodTraceInfo> findInTrace(root, methodName)` | 在树中按 methodName 查找节点 |
| `boolean traceContainsChildOf(root, childClass, childMethod)` | 验证父子嵌套 |
| `@Override void close()` | 关闭两个 context |

**多实例策略**：第二实例通过 `SpringApplication.run(MethodTraceLogTestApplication.class, props)` 起独立 `ConfigurableApplicationContext`，props 覆盖 `server.port` + `logging.file.name=logs/app-b.log` + 同样的 api key。

### 新增 host 端点

落在 `TestController.java`：

| 端点 | 用途 |
|---|---|
| `GET /test/slow?sleepMs=N` | 线程 sleep 触发 SlowMethodAnalyzer 数据点 |
| `GET /test/sampled?iterations=N` | 反复调用 root 方法（验证采样计数） |
| `GET /test/throw?n=N&message=m` | 控制抛异常次数（AlertingService 阈值测试） |
| `GET /test/throw-from?class=FQN&n=N` | 指定抛异常的类（验证 classes[] 白名单） |
| `GET /test/cors-info` | 回显 `Origin` header（验证 CORS 响应头） |
| `GET /test/otel-out?port=` | 出站调用对端 `/test/aspectLog`（验证 OTel trace 一致） |

**不动**：`/test/blacklist`（已存在）、`/test/callRemote*`（已存在）、`/test/_test/echo-webhook`（已存在）。

### JUnit IT 类清单

落在 `methodTraceLog-test/.../e2e/`，命名 `*IT`（integration test）：

| 类 | 深度 | 多实例 | 关键断言 |
|---|---|---|---|
| `TracePropagationIT` | deep | ✅ | 出站 traceid ≡ 对端入站 traceid；RestClient + RestTemplate 双链路 |
| `OtelPropagationIT` | deep | ✅ | OTel `Span.current().traceId()` ≡ internal `traceid` |
| `AlertingIT` | deep | ❌ | 触发 3 次异常 → webhook 收到 1 次；cooldown 内不重复；classes 白名单外不触发 |
| `SlowMethodIT` | deep | ❌ | sleep 触发 → MCP `getSlowMethods` 看到该 method 在 topN |
| `SamplingIT` | deep | ❌ | `sampleRate=0` → /view/list 0 条；`=1` → 全部；`=0.5` → ~50% |
| `ExcludePatternIT` | deep | ❌ | `/test/blacklist` 调用后 `/view/list` 含 describe/doWork，不含 equals/hashCode/toString |
| `TraceStoreIT` | smoke-deep | ❌ | in-memory / file / none 三种 store 切换后 `/view/list` 行为 |
| `LogFileQueryIT` | smoke | ❌ | `/logFile/files` 200 + 含当前 app log；`/logFile/query` 关键字过滤 |
| `LogFileMonitorIT` | smoke | ❌ | `/logFile/monitor/start` + `/status` + `/stop` 状态机 |
| `DecompileIT` | smoke | ❌ | 正常方法 → 源码；不存在方法 → 404 |
| `SessionAuthIT` | smoke | ❌ | `/login` 拿到 cookie；带 cookie 访问 200；不带 401；`/logout` 清空 |
| `CorsIT` | smoke | ❌ | preflight OPTIONS 204 + CORS 头；正常请求带 Origin → 响应有 CORS 头 |
| `PanelIT` | smoke | ❌ | `/panel` 200 + HTML 体积 > 10KB + 含 4 个 tab 名 |
| `McpIntegrationIT` | smoke | ❌ | 通过 ProcessBuilder 起 methodTraceLog-mcp.jar，调 15 个工具至少各一次 |

### Agent MCP 验证流程（本会话内执行）

1. `mvn install -DskipTests` — 编译 starter + autoconfigure + mcp + test
2. `java -jar methodTraceLog-test/target/methodTraceLog-test-1.0-SNAPSHOT.jar` — 启 host 8085
3. `jbang ./methodTraceLog-mcp/target/methodTraceLog-mcp-1.0-SNAPSHOT.jar --method-trace-log.mcp.hosts[0].name=local-dev --method-trace-log.mcp.hosts[0].url=http://localhost:8085 --method-trace-log.mcp.hosts[0].api-key=change-me-in-production` — 启 MCP（stdio）
4. 通过 `mcp__methodTraceLog-mcp__*` 工具调 15 个工具各一次（happy path）
5. 输出每步响应摘要到对话
6. `mvn -pl methodTraceLog-test test -Dtest='cn.wubo.method.trace.log.e2e.*IT'` — 跑 JUnit
7. 对比 MCP 响应 vs JUnit 断言，记录差异

### 文件清单

```
docs/superpowers/specs/2026-08-29-full-coverage-e2e-design.md   ← 本文件
docs/superpowers/plans/2026-08-29-full-coverage-e2e-plan.md      ← 后续 writing-plans 产出
methodTraceLog-test/src/main/java/cn/wubo/method/trace/log/
  e2e/MtlE2eHarness.java                                         ← 共享 harness
  TestController.java                                            ← 新增 6 个端点
methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/
  e2e/{14 个 IT 类}.java
TEST_REPORT.md                                                   ← 本轮 e2e 报告
```

## Tech Stack

- Java 17, Spring Boot 3.5
- JUnit 5（已用）、AssertJ（已用）
- 不引入新依赖
- 多实例 = 同进程多 `ConfigurableApplicationContext`

## Global Constraints

- `mvn install -DskipTests -Dgpg.skip=true` 必须 BUILD SUCCESS
- `mvn -pl methodTraceLog-test test -Dtest='cn.wubo.method.trace.log.e2e.*IT'` 必须全绿
- MCP 路径响应与 JUnit 断言必须一致；不一致 → 记录到 `TEST_REPORT.md`「Known issues」
- 微 bug（1-2 行）现场修 + 加单测；大 bug 现场记录到 `TEST_REPORT.md` 后停顿等用户决定
- 不动 starter 模块代码（除非微 bug 修复）
- 测试日志路径：`logs/app-a.log`（主）+ `logs/app-b.log`（副），避免污染
- host app 端口：主 8085，副 8086；副通过 `-Dserver.port=8086` 覆盖

## Error Handling

| 失败场景 | 策略 |
|---|---|
| 多实例端口冲突 | JVM 内随机端口 + JUnit `@AfterAll` 关 context |
| Webhook 时序 | `awaitWebhook(minCount, 5s)` 轮询，禁止固定 `sleep` |
| MCP jar 启动失败 | `ProcessBuilder` + 5s ready 等待 + 失败时 dump stderr |
| OTel jar 不在 classpath | `OtelPropagationIT` 用 try/catch + `Assumptions.assumeTrue(...)` 跳过 |
| 测试日志污染 | 主/副实例不同 `logging.file.name` |
| MethodTraceInfo 类位置 | harness 直接 import starter 模块的 `MethodTraceInfo`（不要复制） |

## Out of Scope

- 单元测试（已 35 类覆盖）
- 性能 / 内存 / 吞吐量压测
- Spring Boot / Java 升级兼容性
- 多协议 trace 透传（gRPC、Kafka、RocketMQ）
- WebSocket STOMP 客户端验证（用 REST 替代：`/logFile/monitor/{start,stop,status}`）
- 浏览器面板 UI 测试（仅 smoke：200 + 体积 + 关键 tab 名）
