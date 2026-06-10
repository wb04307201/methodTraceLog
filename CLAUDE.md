# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

`methodTraceLog` is a Spring Boot starter that wraps application code with a method-tracing AOP aspect. For every intercepted method it emits a `ServiceCallInfo` (traceid / spanid / pspanid, class, signature, args, return value or exception, action enum, timestamp) which is then fanned out to a list of `ICallService` beans. Two are built in: one writes a structured log line, the other records Micrometer `Timer` samples and builds a parent/child `MethodTraceInfo` tree that the web panel renders. A second feature, gated by `method-trace-log.file.enable`, reads/parses log files via `NIO WatchService` and tail-pushes new lines over STOMP to `/topic/log-monitor`. The decompiler (`DecompilerUtils`, CFR-backed) is exposed as an HTTP endpoint at `GET /methodTraceLog/decompile`, gated by an optional `X-Api-Key` filter.

A companion module `methodTraceLog-mcp` is a separate, standalone Spring Boot process that speaks Model Context Protocol over stdio and forwards `@Tool` calls to one or more hosts (each host is a separate app that has the starter on the classpath) over HTTP. This is how AI agents get access to the trace / log / decompile data without bringing in any AI framework themselves.

The published artifact is `com.gitee.wb04307201.methodTraceLog:methodTraceLog-spring-boot-starter`. The working tree builds `1.0-SNAPSHOT`.

## Build, test, run

Maven multi-module project, Java 17. Run from the repo root.

- Build everything and install into the local repo: `mvn install`
- Build/test a single module and its dependencies: `mvn -pl methodTraceLog-test -am package`
- Run all tests: `mvn test`
- Run a single test class: `mvn -pl methodTraceLog-test test -Dtest=AbstractCallServiceTest`
- Run a single test method: `mvn -pl methodTraceLog-test test -Dtest=AbstractCallServiceTest#transContext_withArray_shouldConvertToList`
- Launch the sample app: `mvn -pl methodTraceLog-test spring-boot:run` (the test module does not declare `spring-boot-maven-plugin`; run via `java -cp ...` or by adding the plugin to the module).
- Launch the MCP server: `java -jar methodTraceLog-mcp/target/methodTraceLog-mcp-1.0-SNAPSHOT.jar` (talks over stdio).
- The Maven `mvn` command on this machine must be invoked via `/c/developer/apache-maven-3.9.16/bin/mvn` (the default `mvn` shim is broken).

Module layout:
- `methodTraceLog` — core library (aspect, strategy, properties, services, utils, file + decompiler, panel.html + static assets). No AI dependencies.
- `methodTraceLog-spring-boot-autoconfigure` — `@AutoConfiguration` classes registered through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (`LogConfig`, `LogFileConfig`, `ApiKeyFilter`).
- `methodTraceLog-spring-boot-starter` — empty wrapper that depends on the autoconfigure module.
- `methodTraceLog-mcp` — standalone Spring Boot MCP server (separate process, stdio transport, talks to hosts via HTTP).
- `methodTraceLog-test` — runnable sample + JUnit 5 tests for `AbstractCallService`, `LogActionEnum`, `LogQueryRequest`, `ValidationUtils`, `DecompilerUtils`. No test covers the AOP wiring end-to-end; verify changes by running the sample app and hitting the endpoints.

## Configuration

`method-trace-log.*` is bound by `MethodTraceLogProperties` (`@ConfigurationProperties`). Four top-level groups, each switchable independently:

- `method-trace-log.log.enable` — gates `LogConfig` and the whole aspect/AOP machinery.
- `method-trace-log.file.enable` — gates `LogFileConfig`, STOMP broker, file reading, and the WebSocket controller.
- `method-trace-log.security.api-key` — when non-empty, the `ApiKeyFilter` requires an `X-Api-Key` header on every `/methodTraceLog/**` request except the `/panel` HTML page itself. If empty, the filter is a no-op (development only).
- `method-trace-log.decompile.timeout-seconds` — per-call CFR timeout for the `DecompilerUtils` and the `/methodTraceLog/decompile` endpoint.
- `method-trace-log.log.serviceCalls` — per-`ICallService` enable flags applied at startup. `CallServiceStrategy.setCallServiceEnable(name, enable)` can flip them at runtime via `GET /methodTraceLog/view/callService?name=...&enable=...`.
- `method-trace-log.file.*` — log path, allowed extensions, `maxLines`, `maxFileSize`, and the regex `logPattern` used by `LogLineInfo.parse`. The default pattern matches `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`.
- `management.endpoints.web.exposure.include=methodtrace` — exposes the `MethodTraceLogEndPoint` actuator endpoint used by the panel.

MCP server config (`methodTraceLog-mcp/src/main/resources/application.yml`):
- `method-trace-log.mcp.hosts[].{name,url,description,api-key}` — each host is a `RouterFunction`-serving Spring Boot app. `api-key` (if set) is forwarded as `X-Api-Key` to the host.

Sample app config lives in `methodTraceLog-test/src/main/resources/application.yml`; logback uses `${LOG_DIR}/${APP_NAME}.log` and a daily rolling file.

## Big-picture architecture

Trace path (one method invocation on a `@Component` / `@Service` / `@RestController`, excluding the framework's own `ICallService` / `MethodTraceLogEndPoint` / `LogFileService` / `LogFileRealTimeService` types):

1. `LogAspect.around` (`@Around` advice) reads/creates `traceid` / `spanid` from `MDC` and builds a `ServiceCallInfo` with `LogActionEnum.BEFORE`.
2. `CallServiceStrategy.consumer` iterates every `ICallService` bean, skipping those whose `enable` is false, and invokes `consumer(info)` on each.
3. `jp.proceed()` runs the target method. On normal return, the same `ServiceCallInfo` (a copy from `before` via `ServiceCallInfo.copyOf`) gets its `context` set to the return value and `LogActionEnum.AFTER_RETURN`. On exception, `context = e` and `LogActionEnum.AFTER_THROW`. Both events also fan out through `CallServiceStrategy`.
4. `MDC` is restored in `finally` so nested calls keep the parent span.

`SimpleLogServiceImpl` is the default log writer (uses `transContext` to coerce args/return/exception into something loggable — arrays, `HttpServletRequest/Response`, `MultipartFile`, `ResponseEntity`, `Exception` are all special-cased).

`SimpleMonitorServiceImpl` does two things on every event:
- Starts/stops a Micrometer `Timer.Sample` keyed by `spanid`, registered under meter name `method.execution.time` with tags `className`, `methodSignature`, `action` (`AFTER_RETURN` vs `AFTER_THROW`).
- Maintains a `MethodTraceInfo` tree by `spanid` / `pspanid`. Root calls are kept in `methodTraceInfos` for at most 8h (`MAX_LOG_AGE_MILLIS`); the cleanup runs every time a new root call comes in. `getByTraceId(id)` walks the root list to find a matching trace.

`MethodTraceLogEndPoint` is the `@Endpoint(id = "methodtrace")` actuator endpoint. Its `@ReadOperation` runs a `Search.in(meterRegistry).name("method.execution.time").timers()` query, groups by `className#methodSignature`, and returns success/failure counts and timings for the panel.

`LogFileService` lists files under `method-trace-log.file.path` (extension filter and size cap enforced in `getFile`), then for queries runs every line through `LogLineInfo.parse` with the configured regex and applies the request's keyword / level / time-range filters. The `FileUtils.pathInspection` whitelist (`[a-zA-Z0-9._-]+`, no `..`, ≤255 chars) is the path-traversal guard called from both `LogFileService` and `LogFileRealTimeService`.

`LogFileRealTimeService` registers the log directory with `java.nio.file.WatchService` and pushes deltas to `/topic/log-monitor`. `LogFileConfig` enables STOMP (`/topic` broker, `/app` prefix, `/ws` SockJS endpoint) and exposes the inner `LogWebSocketController` for `@MessageMapping` start/stop/heartbeat, plus REST endpoints at `/methodTraceLog/logFile/monitor/{start,stop,status}` for non-WebSocket clients (and the MCP server).

`DecompilerUtils.decompile(className, methodName, [timeoutSeconds])` is the entry point. Internally: resolves the class via `Class.forName(name, false, ctxCl)`, reads the class bytes through `cl.getResourceAsStream(...)` (so Spring Boot fat-jar nested jars work without URL string surgery), writes them to a temp file, and runs CFR via the official `CfrDriver.Builder` + `OutputSinkFactory` API. CFR is invoked on a daemon thread from a `cached` pool with a future timeout; on timeout the future is cancelled and an `IllegalStateException` is thrown. `removeAnnotations(code)` strips `@Foo(...)` and bare `@Bar` lines for LLM-friendly output.

`ApiKeyFilter` (`OncePerRequestFilter`) is registered as a `FilterRegistrationBean` with URL pattern `/methodTraceLog/*` and `Ordered.HIGHEST_PRECEDENCE`. It skips the filter entirely for non-`/methodTraceLog/` paths and the `/methodTraceLog/panel` HTML page; for the rest, it requires `X-Api-Key` to equal `method-trace-log.security.api-key` (when configured). OPTIONS preflight is allowed unconditionally. 401 responses are returned as plain JSON.

`methodTraceLog-mcp` (`MethodTraceLogMcpApplication`) is a standalone Spring Boot process with `web-application-type: none`. `MethodToolCallbackProvider.builder().toolObjects(service).build()` registers all 13 public `@Tool` methods on `MethodTraceLogMcpService` as MCP tools. The service itself just looks up the host by name in `MethodTraceLogMcpProperties.Hosts` and forwards the call via `RestClient`, adding `X-Api-Key` if the host has one configured. Transport is stdio (default for `spring-ai-starter-mcp-server`).

## HTTP surface

All endpoints are registered as `RouterFunction<ServerResponse>` beans (`wb04307201MethodTraceLogRouter` / `…FileRouter`). All except `GET /methodTraceLog/panel` require `X-Api-Key` when `method-trace-log.security.api-key` is set.

- `GET  /methodTraceLog/panel` — single-page panel (4 tabs: 概览 / 调用记录 / 日志文件 / 反编译). Whitelisted from auth.
- `GET  /methodTraceLog/view/callServices` — list of registered services with current enable flag.
- `GET  /methodTraceLog/view/callService?name=&enable=` — toggle a service.
- `GET  /methodTraceLog/view/list?className=&methodName=&onlyErrors=&limit=` — root `MethodTraceInfo` nodes (newest calls).
- `GET  /methodTraceLog/view/traceid?id=` — full tree for a trace.
- `GET  /methodTraceLog/view/export?format=json|csv&className=&methodName=&onlyErrors=&limit=` — bulk export (default limit 1000).
- `GET  /methodTraceLog/decompile?className=&methodName=&timeoutSeconds=` — text/plain CFR-decompiled source, annotations stripped.
- `GET  /methodTraceLog/logFile/files` — list of readable log files.
- `POST /methodTraceLog/logFile/query` body: `LogQueryRequest` — paginated, filtered lines.
- `POST /methodTraceLog/logFile/download` body: `LogQueryRequest` — text stream download.
- `GET  /methodTraceLog/logFile/monitor/start?fileName=` — start tailing a file (REST equivalent of the STOMP start-monitor).
- `GET  /methodTraceLog/logFile/monitor/stop?fileName=` — stop tailing.
- `GET  /methodTraceLog/logFile/monitor/status` — current monitor state.
- `WS   /ws` (SockJS) STOMP destinations: `/app/start-monitor`, `/app/stop-monitor`, `/app/monitor-status`, `/app/heartbeat`; broker pushes to `/topic/log-monitor`.
- `GET  /actuator/methodtrace` — Micrometer-derived method statistics (requires `management.endpoints.web.exposure.include=methodtrace`).

## Web panel (`panel.html` + `META-INF/resources/static/panel.*`)

A single 4-tab page that replaces the old `view.html` + `logFile.html` pair:

- **概览** (`overview.js`) — summary cards, service toggles, auto-refresh, method statistics table, recent 8h trace table, trace-tree modal.
- **调用记录** (`traces.js`) — className / methodName / onlyErrors / limit filter form, JSON & CSV export, call-chain modal.
- **日志文件** (`logs.js`) — file list, search form, pagination, real-time tail over STOMP. WS connection is started when the tab is shown and deactivated when hidden.
- **反编译** (`decompile.js`) — className / methodName / timeoutSeconds form posting to `/methodTraceLog/decompile` and rendering text into a `<pre>`.

Tab router is `window.MTL` (`registerTab`, `showTab`, `toast`, `openModal`, helpers). All four tab scripts call `MTL.registerTab(name, {onShow, onHide})` from `DOMContentLoaded`. Hash routing (`#overview` default) is read on load and on `hashchange`. Auth + login modal is `mtlAuth.js` (unchanged, still provides `mtlFetch` / `mtlLogout` / `mtlCheckAuth` / `mtlRenderLogout`). CSS is a single `panel.css` (tokens + topbar + per-tab sections).

## MCP tool surface (exposed by `methodTraceLog-mcp`)

| Tool | Purpose |
|---|---|
| `getHosts` | List configured hosts |
| `ping` | Verify a host is reachable (hits `/actuator`) |
| `getCallServices` | List log services + enable state on a host |
| `setCallServiceEnable` | Enable/disable a log service on a host |
| `getMethodTraceList` | Recent method-call trace records on a host |
| `getMethodTraceByTraceId` | Full call chain for a trace id on a host |
| `decompileMethod` | Decompile a class+method on a host, returns source |
| `getLogFiles` | List files in a host's log directory |
| `queryLogContent` | Filter log lines by keyword / time / level on a host |
| `downloadLog` | Stream log file content from a host |
| `startMonitor` / `stopMonitor` / `getMonitorStatus` | Tail log files in real time on a host |

All tool parameters are declared with `@ToolParam(description = ...)` and the parent POM compiles with `<parameters>true</parameters>`, so the JSON-RPC schema uses real parameter names (not `arg0`, `arg1`).

## Extension points

- New `ICallService`: extend `AbstractCallService`, annotate as `@Component`, implement `consumer(ServiceCallInfo)`, `getCallServiceName`, `getCallServiceDesc`. The bean is auto-picked up by `CallServiceStrategy`. To start disabled, add `- { name: YourName, enable: false }` under `method-trace-log.log.serviceCalls`.
- New MCP tool: add a public method to `MethodTraceLogMcpService` annotated with `@Tool(description = "...")` and `@ToolParam` on each parameter. The `MethodToolCallbackProvider` bean picks it up automatically; no further wiring needed.

## Gotchas worth remembering

- `LogAspect` explicitly excludes the framework's own types via `!within(...)`. Adding new internal classes that should also be invisible to the aspect requires extending the pointcut expression in `LogAspect.java`.
- `SimpleMonitorServiceImpl.methodTraceInfos` is an in-memory list pruned on a best-effort basis during new root-call processing. Long-lived processes with low traffic can keep stale entries up to 8h; high traffic causes the prune to fire on every root call.
- The default `logPattern` only matches the standard logback pattern. If `logback.xml` is changed, the regex in `application.yml` must be updated or `LogLineInfo.parse` will return unparsed lines and keyword/level/time filters will not work.
- The `LogAspect` uses `MDC` keys `traceid` / `spanid` / `pspanid`. Custom logback patterns can include `%X{traceid}` etc. to correlate logs across services.
- The CFR decompiler reads class bytes through the classloader's `getResourceAsStream`. This works uniformly for file paths, thin jars, and Spring Boot fat-jar nested jars — do **not** try to parse the `URL.getPath()` string of the resource.
- The CFR decompiler is invoked on a daemon thread with a future timeout. CFR running on a pathologic input will be cancelled cleanly, but the temp file is always cleaned up in `finally`.
- This is a Windows / IntelliJ project (`.idea/` is present and `.gitignore` ignores `.idea`, `*.iws`, `*.iml`); use the Windows shell syntax hints already in this environment when running shell tools.
