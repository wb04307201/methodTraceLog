# Method Trace Log

<div align="right">
  English | <a href="README.zh-CN.md">中文</a>
</div>

> Spring Boot starter for method tracing, performance monitoring, log file management, and CFR-backed decompilation. Includes a standalone MCP server (`methodTraceLog-mcp`) that exposes the same capabilities to AI agents over stdio.

[![](https://jitpack.io/v/com.gitee.wb04307201/methodTraceLog.svg)](https://jitpack.io/#com.gitee.wb04307201/methodTraceLog)
[![star](https://gitee.com/wb04307201/methodTraceLog/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![fork](https://gitee.com/wb04307201/methodTraceLog/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/methodTraceLog)
[![star](https://img.shields.io/github/stars/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
[![fork](https://img.shields.io/github/forks/wb04307201/methodTraceLog)](https://github.com/wb04307201/methodTraceLog)
![MIT](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Spring%20Boot-3+-green.svg)

![gif.gif](gif.gif)

---

## Features

| | |
|---|---|
| **Method tracing** | AOP-based, full call chain with `traceid` / `spanid` / `pspanid`; sampling; `@AspectLog` to override the display name |
| **Metrics** | Micrometer `Timer` per method + parent/child `MethodTraceInfo` tree; exposed via `actuator/methodtrace` |
| **Log file viewer** | Read / filter / download log files; WebSocket live tail; path-traversal safe |
| **CFR decompile** | HTTP endpoint for any class on the classpath (app / 3rd-party / fat-jar nested), annotations stripped |
| **OTel export** | Auto-bridges trace events to OpenTelemetry OTLP/HTTP when `opentelemetry-sdk` is on the classpath |
| **W3C traceparent** | Auto-injects / extracts `traceparent` header for HTTP inbound and `RestClient` outbound |
| **Cookie session** | Browser login via `POST /methodTraceLog/login`, HTTP `X-Api-Key` for CLI / MCP |
| **MCP server** | Standalone stdio process; 13 tools, multi-host, forwards to your starter over HTTP |

---

## Quick Start

### Add the dependency

```xml
<dependency>
    <groupId>com.gitee.wb04307201.methodTraceLog</groupId>
    <artifactId>methodTraceLog-spring-boot-starter</artifactId>
    <version>1.0.20</version>
</dependency>
```

### Minimal configuration

```yaml
method-trace-log:
  log:
    enable: true
  file:
    enable: true
    path: ./logs
  security:
    api-key: change-me-in-production   # required in production; empty disables auth (dev only)

management:
  endpoints:
    web:
      exposure:
        include: methodtrace           # for the /actuator/methodtrace panel data
```

### Open the panel

`http://localhost:8080/methodTraceLog/panel` — single page with 4 tabs: 概览 (overview) / 调用记录 (trace search + export) / 日志文件 (file viewer + real-time tail) / 反编译 (CFR decompile).

---

## Configuration Reference

```yaml
method-trace-log:
  log:
    enable: true                                  # AOP master switch
    sample-rate: 1.0                              # 0.0 ~ 1.0; child spans inherit parent decision
    service-calls:                                # start-up enable flags
      - { name: CustomLog,         enable: false }   # 3 built-in services: SimpleLogService / SimpleMonitorService / CustomLog
    trace-store:                                  # where the in-memory tree lives
      type: in-memory                             # in-memory | file | none
      path: ./trace-store                         # only when type=file (auto-creates yyyy-MM-dd subdirs)
      ttl-millis: 28800000                        # 8h
      max-traces: 10000                           # recent map cap
  file:
    enable: true
    path: ./logs
    allowed-extensions: [.log, .txt, .out]
    max-lines: 1000
    max-file-size: 100                            # MB
    # log-pattern: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[([^\]]+)\]\s+(\w+)\s+([^\s]+)\s*-\s*(.*)
  security:
    api-key: change-me-in-production              # empty = no auth
    session:
      ttl-millis: 28800000                        # 8h sliding session for browser cookies
  decompile:
    timeout-seconds: 10                           # CFR daemon-thread timeout
  otel:                                           # requires opentelemetry-sdk on classpath
    enable: false
    endpoint: http://localhost:4318/v1/traces
    service-name: method-trace-log
  propagate:                                      # W3C traceparent propagation
    http-inbound: true                            # TraceContextFilter reads traceparent
    rest-client-outbound: true                    # RestClient.Builder interceptor
    rest-template-interceptor: true               # exposes a RestTemplate interceptor bean
```

---

## HTTP Surface

All routes require `X-Api-Key` (or `MTRACE_SESSION` cookie) when `security.api-key` is non-empty, **except** `/methodTraceLog/panel` (HTML).

| Method | Path | Purpose |
|---|---|---|
| GET | `/methodTraceLog/panel` | HTML panel — 4 tabs (whitelisted) |
| GET | `/methodTraceLog/view/callServices` | List services + enable state |
| GET | `/methodTraceLog/view/callService?name=&enable=` | Toggle a service at runtime |
| GET | `/methodTraceLog/view/list?className=&methodName=&onlyErrors=&limit=` | Recent root traces |
| GET | `/methodTraceLog/view/traceid?id=` | Full call chain for a trace id |
| GET | `/methodTraceLog/view/export?format=json\|csv&className=&methodName=&onlyErrors=&limit=` | Bulk export (default limit 1000) |
| GET | `/methodTraceLog/decompile?className=&methodName=&timeoutSeconds=` | Text/plain source |
| GET | `/methodTraceLog/logFile/files` | List log files in the configured dir |
| POST | `/methodTraceLog/logFile/query` | Filter / paginate / time-range / level |
| POST | `/methodTraceLog/logFile/download` | Stream the same content as text |
| GET | `/methodTraceLog/logFile/monitor/{start,stop,status}?fileName=` | REST live-tail (companion to STOMP) |
| POST | `/methodTraceLog/login` | Body `{"apiKey":"..."}` → `Set-Cookie: MTRACE_SESSION=...` |
| POST | `/methodTraceLog/logout` | Invalidate session |
| GET | `/methodTraceLog/session/status` | `{ sessionValid: true/false }` |
| WS | `/ws` (SockJS) → `/topic/log-monitor` | Live log lines; STOMP send to `/app/{start-monitor,stop-monitor,monitor-status,heartbeat}` |
| GET | `/actuator/methodtrace` | Per-class / per-method Micrometer stats |

---

## MDC Trace IDs

`LogAspect` puts `traceid` / `spanid` / `pspanid` into SLF4J MDC. Use them in your logback pattern to correlate logs across the call chain:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] [trace=%X{traceid} span=%X{spanid}] %-5level %logger - %msg%n</pattern>
```

For HTTP boundaries, `TraceContextFilter` reads `traceparent` on the way in, and the `RestClient` interceptor writes it on the way out — upstream and downstream `traceid`s join automatically.

---

## Web Panel Authentication

When `security.api-key` is non-empty, the panel UI uses a top **banner** (not a modal) to collect the API Key:

- Banner is shown above the tab content the first time you visit `/methodTraceLog/panel` without a valid session.
- Submitting a valid Key (button or **Enter**) hides the banner, sets the `MTRACE_SESSION` cookie, and **auto-loads the current tab's data** — no manual refresh.
- Wrong / empty Key shows an inline error (`请输入 API Key` / `❌ API Key 无效或鉴权未启用`).
- Session is a **sliding 8-hour cookie** (`security.session.ttl-millis`, default 28 800 000). It renews on use, so you only re-enter the Key after 8h of inactivity or after clicking the **🚪 注销** button.
- The logout button only appears in the header when a session is active; the `/methodTraceLog/logout` endpoint invalidates the cookie and re-mounts the banner.
- In dev mode (`api-key: ""`), the banner is suppressed entirely and no logout button is shown.

`GET /methodTraceLog/session/status` returns `{ authEnabled, sessionValid }` so the panel JS can decide whether to show the banner. While the banner is up, the in-page `mtlFetch` queues 401 responses and replays them on successful login, so the user never sees an "unauthorized" toast during the login flow.

CLI / MCP clients should keep using the `X-Api-Key` header directly — the cookie session is purely a browser convenience.

---

## `@AspectLog` Annotation

Method-level opt-in (the class doesn't have to be a `@Component`). Use it to rename the method in the trace tree and on the OTel span:

```java
public class MyHelper {
    @AspectLog("do-something")
    public void doSomething(String s) { ... }
}
```

The trace list and OTel span name will show `do-something` instead of the raw method signature.

---

## Custom `ICallService`

Extend `AbstractCallService` and implement `consumer(ServiceCallInfo)`. It's auto-picked up by the `CallServiceStrategy`:

```java
@Component
public class MyService extends AbstractCallService {

    @Override
    public void consumer(ServiceCallInfo info) {
        // logActionEnum is one of BEFORE / AFTER_RETURN / AFTER_THROW
        log.info("{} {}", info.getClassName(), info.getMethodName());
    }

    @Override public String getCallServiceName() { return "MyService"; }
    @Override public String getCallServiceDesc() { return "My desc"; }
}
```

Start it disabled: `service-calls: [{ name: MyService, enable: false }]`, then flip via the panel.

---

## MCP Server

A separate, standalone Spring Boot process that speaks Model Context Protocol over stdio. It forwards `@Tool` calls to one or more hosts (each host = an app that has the starter on the classpath) over HTTP.

```xml
<dependency>
    <groupId>com.gitee.wb04307201.methodTraceLog</groupId>
    <artifactId>methodTraceLog-mcp</artifactId>
    <version>1.0.20</version>
</dependency>
```

```yaml
method-trace-log:
  mcp:
    hosts:
      - { name: local-dev, url: http://localhost:8080, description: Local dev,   api-key: change-me-in-production }
      - { name: staging,   url: https://staging.example.com, description: Staging, api-key: ${STAGING_API_KEY} }
```

Launch the released jar with stdio transport, configured in your AI client (Claude Desktop, Cursor, ...):

```bash
java -jar methodTraceLog-mcp-1.0.20.jar
```

**13 tools exposed:** `getHosts`, `ping`, `getCallServices`, `setCallServiceEnable`, `getMethodTraceList`, `getMethodTraceByTraceId`, `decompileMethod`, `getLogFiles`, `queryLogContent`, `downloadLog`, `startMonitor`, `stopMonitor`, `getMonitorStatus`.

---

## Gotchas

- **`LogAspect` exclusions:** framework-internal types (`ICallService`, `MethodTraceLogEndPoint`, `LogFileService`, `LogFileRealTimeService`) are excluded. Add yours to the pointcut if you have similar internal beans.
- **Path traversal:** `FileUtils.pathInspection` is the whitelist for `LogFileService` and `LogFileRealTimeService` — `[a-zA-Z0-9._-]+`, no `..`, ≤ 255 chars.
- **Log pattern:** the default `log-pattern` only matches the standard logback pattern. If you change `<pattern>` in `logback.xml`, update `log-pattern` in YAML or `LogLineInfo.parse` will not split keyword / level / time.
- **CFR resources:** decompile reads class bytes through the classloader's `getResourceAsStream` — works uniformly for file paths, thin jars, and Spring Boot fat-jar nested jars. Don't parse the `URL.getPath()` string.
- **Spring Boot fat-jar:** the test module does not declare `spring-boot-maven-plugin`. Use `mvn package` then `java -cp target/classes;<classpath> cn.wubo.method.trace.log.MethodTraceLogTestApplication`.
- **Maven on this machine:** invoke `mvn` as `/c/developer/apache-maven-3.9.16/bin/mvn` — the default `mvn` shim is broken.
