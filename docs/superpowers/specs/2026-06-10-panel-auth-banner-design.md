# Panel 鉴权登录改版 — 设计文档

**日期**: 2026-06-10
**状态**: 设计已确认,等待实现
**关联任务**: 修复 `/methodTraceLog/panel` 登录后页面无响应 + 改用更优雅的登录交互

## 背景与现状

`methodTraceLog` 控制台(`/methodTraceLog/panel`)在启用 `method-trace-log.security.api-key` 后,浏览器访问会先弹一个登录模态框。当前实现存在两个问题:

### 问题 1:登录后页面无响应,需手动刷新

**根因分析**(基于 `panel.js` + `mtlAuth.js` 时序):

1. `panel.html` 加载时,4 个 `<section class="mtl-tab-panel">` 全部 `hidden`。
2. `panel.js` 在 `DOMContentLoaded` 调用 `mtlCheckAuth().then(go)`。
3. `go()` 只在 `mtlCheckAuth` resolve 后才调用 `onHashChange()` → `showTab()`,**未登录时根本不会渲染任何 tab**。
4. 用户在模态框输入 API Key 并提交后:
   - `doLogin` 同步派发 `mtlAuthLoginSucceeded` 事件 → `onLoginSucceeded` 被调用,但此时 `currentTab === null`(因为 `go()` 还没跑),所以事件处理器什么也不做。
   - `submitLogin` 关闭模态框,重试 pending 请求。
   - `mtlCheckAuth` 内的 polling 每 400ms 探测 session 状态,首次检测到 `sessionValid: true` 才 resolve。
   - `go()` 被调用,`showTab('overview')` 渲染骨架,`onShow` 触发数据加载。
5. **现象**:模态框关闭后,页面仍保持空白,直到 polling 命中 + `showTab` 真正执行(约 0–400ms 后)。即便时序正常,事件和 promise 这两条路径的竞态也容易让初学者感到"登录后啥也没发生",于是手动刷新。

### 问题 2:模态框交互不优雅

- 模态框是黑底遮罩 + 居中弹窗,首次访问就把整个页面压黑,用户看不到任何内容骨架。
- 没有任何"为什么需要登录 / 在哪里配置 key"的指引。
- 登录失败只能再弹一次模态框,流程僵化。

## 设计目标

1. **页面骨架立即可见**:tab 标题/侧栏/默认占位内容应第一时间渲染,而不是等鉴权完成。
2. **登录与渲染解耦**:鉴权状态变化不应阻塞 UI 骨架的渲染。
3. **单源真相**:由 `panel.js` 显式控制 tab 渲染与数据加载,不再依赖"事件 + Promise 双重机制"。
4. **保留后端与现有 API 兼容性**:`/login` `/logout` `/session/status` 端点、`MtlSessionService`、`ApiKeyFilter` 全部保持不动。
5. **兼容无 Key 模式**:`security.api-key` 为空时,不显示任何鉴权 UI(开发/本地场景)。
6. **不引入 localStorage 记住 Key**:依赖 cookie + session(浏览器关闭会随 session 失效而重新要求)。
7. **TTL 内保留 session**:不要求用户每次刷新都重新输入 key(默认 8 小时)。

## 设计方案

### 总体架构:页面骨架 + 顶部横幅

**核心变更:**

- 用**顶部横幅**(`.mtl-auth-bar`)替换模态框。
- `mtlCheckAuth()` 改为**只查询**,不再触发任何 UI 行为。
- 新增 `mtlMountAuthBanner(containerId, onSuccess)` API,用于把横幅渲染到指定容器。
- `mtlFetch` 在 401 时改抛错(`Error('unauthorized')`),由调用方决定下一步(默认由 `mtlMountAuthBanner` 处理)。
- 保留 `mtlAuthLoginSucceeded` 事件(用于 mtlFetch 内部队列重试),但 `panel.js` **不再依赖**此事件来决定 tab 渲染/数据加载。

### 视觉与布局

横幅位置:`<header class="mtl-topbar">` 下方,`<main class="mtl-main">` 之前。

```
┌──────────────────────────────────────────────────────────────────┐
│ 📊 方法观测日志    [概览][调用记录][日志文件][反编译]  [🚪注销]   │
├──────────────────────────────────────────────────────────────────┤
│ 🔑 请输入 API Key  [_____________________] [登录]    ← 仅未登录 │
├──────────────────────────────────────────────────────────────────┤
│ [调用次数 0]  [成功 0]  [成功率 0%]                              │
│ ...                                                              │
└──────────────────────────────────────────────────────────────────┘
```

**横幅样式要点:**
- 背景:淡黄色/琥珀色(`#fff7e6` 系),不抢眼但有视觉提示。
- 左侧:🔑 图标 + "请输入 API Key" 文字。
- 右侧:输入框 + "登录"按钮。
- 错误态:输入框红边 + 下方行内错误文字(红色),**不**清空已输入的值(便于修正)。
- 加载态:按钮文字"登录中…",禁用按钮。
- 成功过渡:横幅向上滑出(200ms)后 `display: none`,同时调用 `onSuccess` 回调。

### 时序

**页面加载:**

```
DOMContentLoaded
  ├─ showTab('overview', {skipOnShow: true})   // 仅渲染骨架,onShow 推迟
  ├─ mtlCheckAuth()
  │     ├─ authEnabled=false                   // 调 loadCurrentTabData() → onShow
  │     ├─ sessionValid=true                   // 调 loadCurrentTabData() → onShow
  │     └─ authEnabled && !sessionValid        // mtlMountAuthBanner,等用户登录
  └─ 监听 hashchange
```

**用户提交:**

```
用户点击登录(或按 Enter)
  ├─ 按钮禁用,文案改"登录中…"
  ├─ POST /methodTraceLog/login (X-Api-Key header)
  │     ├─ 200 → 关闭横幅,调用 onSuccess
  │     │       onSuccess: 主动调用 tabModules[currentTab].onShow() 拉数据
  │     │                  + 派发 mtlAuthLoginSucceeded(供 mtlFetch 内部重试)
  │     └─ 401 → 显示行内错误"API Key 无效",保持输入框焦点
  └─ 网络异常 → 显示行内错误"网络异常:{msg}"
```

**关键改动**:登录成功路径**不再**依赖 `mtlAuthLoginSucceeded` 事件触发数据加载;`panel.js` 显式在横幅 onSuccess 回调里调用 `tabModules[currentTab].onShow()`。

### 详细 API 改动

#### `mtlAuth.js`

| 名称 | 旧行为 | 新行为 |
|------|--------|--------|
| `window.mtlCheckAuth()` | 检查 session;若需登录则打开模态 + 轮询 | **纯查询**:只调用 `/session/status` 返回 `{authEnabled, sessionValid}` |
| `window.mtlFetch(url, options)` | 401 时自动打开模态,登录成功后重试 | 401 时抛 `Error('unauthorized')`,由调用方决定下一步 |
| `window.mtlMountAuthBanner(containerId, onSuccess)` | (不存在) | 渲染横幅到 `containerId`,登录成功调 `onSuccess`;重复调用同一 id 则幂等替换 |
| `window.mtlUnmountAuthBanner(containerId)` | (不存在) | 隐藏并移除横幅 DOM |
| `window.mtlLogout()` | 调用 `/logout` 后打开模态 | 调用 `/logout` 后**重新挂横幅**:mtlAuth 内部维护一个注册表(`mtlMountAuthBanner` 调用时注册 slot + onSuccess),`mtlLogout` 命中注册表则调 `onSuccess` 之外的"未鉴权"分支,直接显示横幅 |
| `window.mtlRenderLogout(containerId)` | 不变 | 不变 |
| `window` 事件 `mtlAuthLoginSucceeded` | 由 `doLogin` 派发 | **保留派发**,但 `panel.js` 不再依赖 |

#### `panel.js`

- `DOMContentLoaded` 立即调用 `showTab('overview')`(若已挂载 tab 模块)。
- 监听 `mtlAuthLoginSucceeded` 事件:仍保留,用于兼容未来场景,但**当前不作为主路径**。
- `mtlCheckAuth().then(state)`:
  - `state.authEnabled && !state.sessionValid` → `mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess)`(因为首次 `showTab` 用了 `skipOnShow: true`,不调 `onShow`,骨架已渲染但不拉数据)。
  - 其他情况 → 调 `loadCurrentTabData()` 拉数据(此时 `currentTab` 已是 `'overview'`)。
- `onBannerSuccess` 回调:
  1. 隐藏横幅(`mtlUnmountAuthBanner`)。
  2. 调用 `tabModules[currentTab].onShow()` 加载数据。
  3. 派发 `mtlAuthLoginSucceeded` 事件(供 `mtlFetch` 内部重试,即使目前没有 pending 请求也无害)。

**注意**:`showTab('overview')` 默认会调用 `onShow` 拉数据;但首次进入时**必须由 `mtlCheckAuth` 的结果决定**是否真正拉数据(未登录时不能拉,会 401)。为避免双重调用,`showTab` 增加 `skipOnShow` 选项:

**新的 `panel.js` 入口:**

```javascript
document.addEventListener('DOMContentLoaded', function () {
    if (window.mtlRenderLogout) mtlRenderLogout('mtlLogoutSlot');

    // 1) 立即渲染 tab 骨架(不调 onShow,等鉴权决定)
    showTab('overview', { skipOnShow: true });
    window.addEventListener('hashchange', onHashChange);

    // 2) 检查登录态,决定挂横幅 / 拉数据
    if (window.mtlCheckAuth) {
        mtlCheckAuth().then(state => {
            if (state.authEnabled && !state.sessionValid) {
                window.mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess);
            } else {
                loadCurrentTabData();
            }
        });
    } else {
        loadCurrentTabData();
    }
});

function onBannerSuccess() {
    window.mtlUnmountAuthBanner('mtlAuthSlot');
    loadCurrentTabData();
    // 派发事件,供 mtlFetch 内部重试
    try { window.dispatchEvent(new CustomEvent('mtlAuthLoginSucceeded')); } catch (e) { /* ignore */ }
}

function loadCurrentTabData() {
    const mod = currentTab && tabModules[currentTab];
    if (mod && mod.onShow) {
        try { mod.onShow(); } catch (e) { console.error(e); }
    }
}
```

**关键修复**:`showTab` 增加 `skipOnShow` 选项:

```javascript
function showTab(name, opts) {
    if (!TABS.includes(name)) name = 'overview';

    // 通知旧 tab 隐藏
    if (currentTab && currentTab !== name && tabModules[currentTab] && tabModules[currentTab].onHide) {
        try { tabModules[currentTab].onHide(); } catch (e) { console.error(e); }
    }

    document.querySelectorAll('.mtl-tab-panel').forEach(el => {
        el.hidden = el.dataset.tab !== name;
    });
    document.querySelectorAll('.mtl-tab').forEach(el => {
        el.classList.toggle('active', el.dataset.tab === name);
    });

    currentTab = name;
    if (location.hash !== '#' + name) {
        history.replaceState(null, '', '#' + name);
    }
    if (tabModules[name] && tabModules[name].onShow) {
        if (!opts || !opts.skipOnShow) {
            try { tabModules[name].onShow(); } catch (e) { console.error(e); }
        }
    }
}
```

> **为什么选 `skipOnShow` 选项而不是其他方式**:`tabModules[*].onShow` 在用户主动切换 tab 时(`onHashChange` → `showTab(name)`)**仍然需要被调用**,否则切到"调用记录"等 tab 时不会自动加载数据。`skipOnShow` 只在首次 `DOMContentLoaded` 入口使用,语义清晰、改动最小。

#### `panel.html`

- 在 `<header class="mtl-topbar">` 与 `<main class="mtl-main">` 之间新增:
  ```html
  <div id="mtlAuthSlot"></div>
  ```
- 横幅 DOM 本身由 `mtlMountAuthBanner` 注入,不写死在 panel.html。
- 增加 `<script src="/static/mtlAuth.js"></script>` 已在 head 中,无需改。

#### `panel.css` / 横幅样式

新增 `.mtl-auth-bar` 及子元素样式(写在 `panel.css` 末尾,或由 `mtlAuth.js` 注入到 `<head>`):

```css
.mtl-auth-bar {
    display: flex; align-items: center; gap: 12px;
    padding: 10px 24px;
    background: linear-gradient(90deg, #fff7e6 0%, #ffe7ba 100%);
    border-bottom: 1px solid #ffd591;
    color: #874d00;
    font-size: 14px;
    transition: transform 0.2s ease, opacity 0.2s ease;
}
.mtl-auth-bar.is-hiding { transform: translateY(-100%); opacity: 0; }
.mtl-auth-bar__icon { font-size: 18px; }
.mtl-auth-bar__input {
    flex: 1; max-width: 360px;
    padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px;
    font-size: 14px; outline: none;
}
.mtl-auth-bar__input:focus { border-color: #fa8c16; }
.mtl-auth-bar__input.is-error { border-color: #ff4d4f; background: #fff1f0; }
.mtl-auth-bar__btn {
    padding: 6px 16px; border: none; border-radius: 6px;
    background: #fa8c16; color: white; font-size: 14px;
    cursor: pointer;
}
.mtl-auth-bar__btn:disabled { opacity: 0.6; cursor: not-allowed; }
.mtl-auth-bar__err {
    color: #ff4d4f; font-size: 13px; margin-left: 4px;
}
```

#### 后端

`LogConfig.java`、`ApiKeyFilter.java`、`MtlSessionService.java` **完全不动**。`/login`、`/logout`、`/session/status` 端点行为不变。

### 数据流(完整)

```
┌──────────┐                              ┌──────────┐
│ 浏览器   │                              │ 后端     │
└────┬─────┘                              └────┬─────┘
     │ GET /methodTraceLog/panel              │
     │───────────────────────────────────────→│ (无鉴权,直接返回 HTML)
     │ ← HTML                                │
     │                                        │
     │ GET /methodTraceLog/session/status     │
     │───────────────────────────────────────→│ 免密
     │ ← {authEnabled, sessionValid}          │
     │                                        │
     │  情况 A: authEnabled=false             │
     │  → loadCurrentTabData() → onShow,完成  │
     │                                        │
     │  情况 B: sessionValid=true             │
     │  → loadCurrentTabData() → onShow,完成  │
     │                                        │
     │  情况 C: 需登录                         │
     │  → 挂顶部横幅                          │
     │  → 用户在横幅输入 Key                   │
     │  → POST /methodTraceLog/login (X-Api-Key)
     │───────────────────────────────────────→│ 鉴权,Set-Cookie MTRACE_SESSION
     │ ← 200 {status:ok}                      │
     │  → 隐藏横幅                             │
     │  → 调 tab onShow,拉数据                │
     │  → 派发 mtlAuthLoginSucceeded          │
     │  → mtlFetch 收到事件后重试 pending 请求│
```

### 错误处理

| 场景 | 用户看到的 |
|------|------------|
| 输入空 key | 输入框红边 + "请输入 API Key" |
| Key 错误 | 输入框红边 + "API Key 无效" |
| 网络异常 | 输入框红边 + "网络异常:…" |
| 后端未配置 Key | 不显示横幅(情况 A) |
| 注销后再访问 | session 失效 → 显示横幅 |
| Cookie 过期 | TTL 后首次访问 → 显示横幅 |
| 多次输错 | 横幅一直显示,直到用户输入正确或关闭浏览器 |
| 用户刷新页面 | 情况 B/C 由 session 决定 |

### 测试 / 验证

无新增单元测试(全是 JS UI 改造)。验证流程:

1. `mvn -pl methodTraceLog-test spring-boot:run` 启动样例应用。
2. **场景 1:未启用鉴权**
   - `application.yml` 中 `method-trace-log.security.api-key` 为空。
   - 访问 `/methodTraceLog/panel#overview` → 立即看到 tab 骨架 + 数据,无横幅。
3. **场景 2:首次访问,需要登录**
   - 配置 `api-key: test123`。
   - 清 cookie 访问 → 看到 tab 骨架 + 顶部横幅。
   - 输入空 key → 红边 + 提示。
   - 输入错误 key → 红边 + "API Key 无效"。
   - 输入正确 key → 横幅消失,数据加载。
4. **场景 3:TTL 内刷新**
   - 登录后刷新 → 不再出现横幅,数据直接加载。
5. **场景 4:注销**
   - 点 topbar "🚪注销" → 确认 → 横幅重新出现。
6. **场景 5:多 tab**
   - 登录后切换到"调用记录"tab → 正常加载(`onHashChange` → `showTab(name)` 自动触发 onShow)。
   - 注销后切换 tab → 401 应被 `mtlFetch` 内部抑制(因为已挂横幅),不显示 toast 噪音。
7. **场景 6:在横幅输入时按 Enter**
   - 提交按钮触发登录。
8. **场景 7:取消/离开**
   - 当前实现没有"取消"按钮(横幅一直显示到登录成功)。如果用户不想登录,只能关闭浏览器或去别的页面。可接受。

## 兼容性

- **后端 API**:零变更。
- **mtlFetch 调用方**:所有 tab 模块(overview.js / traces.js / logs.js / decompile.js)使用 `mtlFetch`,其行为从"401 自动弹模态"改为"401 抛 `Error('unauthorized')`"。需要检查:
  - overview.js 中是否有 `.catch(e => MTL.toast('❌ ' + e.message))` 兜底 → 有,会显示"unauthorized"消息,体验略差但不会崩。
  - traces.js / logs.js / decompile.js 同理,需逐一确认。
  - **改进**:在 `mtlAuth.js` 内部,`mtlFetch` 401 时**优先**检查是否已挂横幅,如有则把错误抑制(避免重复弹);如果未挂横幅,才把错误抛给 `.catch`。这样 tab 主动请求不会因为未登录而刷错误。
- **mtlAuthLoginSucceeded 事件**:保留派发,但**唯一**消费者将是 `mtlFetch` 内部重试 pending 请求。`panel.js` 不再监听。

## 文件改动清单

| 文件 | 类型 | 改动量 |
|------|------|--------|
| `methodTraceLog/src/main/resources/META-INF/resources/static/mtlAuth.js` | 重构 | 中(约 100 行) |
| `methodTraceLog/src/main/resources/META-INF/resources/static/panel.js` | 重构 | 小(约 30 行) |
| `methodTraceLog/src/main/resources/panel.html` | 微调 | 加 1 行 `<div id="mtlAuthSlot"></div>` |
| `methodTraceLog/src/main/resources/META-INF/resources/static/panel.css` | 新增 | 约 30 行 |
| `methodTraceLog-spring-boot-autoconfigure/.../LogConfig.java` | 不改 | 0 |
| `methodTraceLog-spring-boot-autoconfigure/.../ApiKeyFilter.java` | 不改 | 0 |
| `methodTraceLog/src/main/java/.../security/MtlSessionService.java` | 不改 | 0 |

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| `mtlFetch` 401 抛错导致 tab 模块 `.catch` 显示 "unauthorized" 噪音 | `mtlFetch` 内部:401 时若已挂横幅则抑制错误,仅 resolve 一个 sentinel(由 mtlAuth 内部处理重试) |
| 横幅 DOM 注入与 `panel.css` 加载顺序竞争 | `mtlMountAuthBanner` 等待 `document.head` 存在后再注入 `<style>`;若已存在则不再注入 |
| `showTab` 跳过 onShow 影响 tab 切换时的数据加载 | `onHashChange` 路径仍走 `showTab(name)`(无 `skipOnShow`),自动触发 onShow |
| 多个 tab 同时挂横幅(理论上不会) | `mtlMountAuthBanner(id, ...)` 用 id 判定,同 id 重复调用只保留最新 |
| 浏览器不支持 `CustomEvent` | 老旧浏览器;项目目标为现代浏览器,可忽略 |

## 不在本次范围内

- 不引入"记住 Key"到 localStorage(已确认不需要)。
- 不改后端任何代码。
- 不改 `MtlSessionService` 的 TTL 默认值(8 小时)。
- 不重写 4 个 tab 模块(overview / traces / logs / decompile)。
- 不改 MCP server 端点或 API。

## 验收标准

- [ ] 关闭 `security.api-key` 时,无任何鉴权 UI 出现,数据正常加载。
- [ ] 开启 `security.api-key`,首次访问看到完整 tab 骨架 + 顶部横幅。
- [ ] 横幅输入框为空提交时,显示行内错误,无网络请求。
- [ ] 横幅输入错误 key,显示"API Key 无效",横幅不消失。
- [ ] 横幅输入正确 key,横幅消失,数据自动加载,**无需手动刷新页面**。
- [ ] 登录成功后 8 小时内刷新页面,不再出现横幅。
- [ ] 注销后刷新页面,横幅重新出现。
- [ ] 4 个 tab(概览 / 调用记录 / 日志文件 / 反编译)在未登录时仍能切换(只显示占位/骨架)。
- [ ] 横幅的输入框按 Enter 键能触发登录。
