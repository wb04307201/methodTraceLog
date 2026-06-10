# Panel Auth Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace modal-based login in `/methodTraceLog/panel` with a top banner, fixing the post-login empty-page bug and improving the login UX.

**Architecture:** Decouple tab rendering from auth check. Page skeleton renders immediately, banner appears in the topbar slot only when auth is required. Single source of truth: `panel.js` decides when to load data; `mtlAuth.js` owns auth state and banner UI. No backend changes.

**Tech Stack:** Vanilla JS (ES5+, IIFE), Spring Boot 3.x, plain CSS. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-10-panel-auth-banner-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `methodTraceLog/src/main/resources/panel.html` | Panel HTML shell | **Modify** — add `<div id="mtlAuthSlot">` between topbar and main |
| `methodTraceLog/src/main/resources/META-INF/resources/static/panel.css` | Panel styles | **Modify** — append `.mtl-auth-bar*` rules |
| `methodTraceLog/src/main/resources/META-INF/resources/static/mtlAuth.js` | Auth state, banner UI, `mtlFetch` wrapper | **Modify** — replace modal with banner; add `mtlMountAuthBanner` / `mtlUnmountAuthBanner`; `mtlCheckAuth` becomes pure query |
| `methodTraceLog/src/main/resources/META-INF/resources/static/panel.js` | Tab routing, auth-aware data loading | **Modify** — add `skipOnShow` to `showTab`; new `loadCurrentTabData` + `onBannerSuccess` |

No backend, no new files, no new tests (UI-only refactor; manual browser verification per spec).

---

## Task 1: Add Auth Slot to Panel HTML

**Files:**
- Modify: `methodTraceLog/src/main/resources/panel.html:29-30`

- [ ] **Step 1: Insert the auth slot div**

Edit `panel.html`. Find the closing `</header>` of `mtl-topbar` (around line 29) and the opening `<main class="mtl-main">` (around line 31). Insert the slot div between them so the result reads:

```html
        <div class="mtl-topbar-right" id="mtlLogoutSlot"></div>
    </header>

    <div id="mtlAuthSlot"></div>

    <main class="mtl-main">
```

The `<div id="mtlAuthSlot"></div>` is a placeholder; `mtlAuth.js` injects banner content into it at runtime.

- [ ] **Step 2: Verify the file still loads**

Open `panel.html` in a browser. Confirm the page renders the same as before (the new div is empty, so it adds zero height). Or simply grep for the new line:

```bash
grep -n 'mtlAuthSlot' methodTraceLog/src/main/resources/panel.html
```

Expected: one match on a single line.

- [ ] **Step 3: Commit**

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git add methodTraceLog/src/main/resources/panel.html
git commit -m "feat(panel): add empty mtlAuthSlot between topbar and main"
```

---

## Task 2: Add Banner Styles to Panel CSS

**Files:**
- Modify: `methodTraceLog/src/main/resources/META-INF/resources/static/panel.css` (append at end of file)

- [ ] **Step 1: Append the banner styles**

Append the following to the end of `panel.css`:

```css
/* ========== mtlAuth banner ========== */
.mtl-auth-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 24px;
    background: linear-gradient(90deg, #fff7e6 0%, #ffe7ba 100%);
    border-bottom: 1px solid #ffd591;
    color: #874d00;
    font-size: 14px;
    transition: transform 0.2s ease, opacity 0.2s ease;
}
.mtl-auth-bar.is-hiding {
    transform: translateY(-100%);
    opacity: 0;
}
.mtl-auth-bar__icon {
    font-size: 18px;
}
.mtl-auth-bar__input {
    flex: 1;
    max-width: 360px;
    padding: 6px 10px;
    border: 1px solid #d9d9d9;
    border-radius: 6px;
    font-size: 14px;
    outline: none;
    transition: border-color 0.15s, background 0.15s;
}
.mtl-auth-bar__input:focus {
    border-color: #fa8c16;
}
.mtl-auth-bar__input.is-error {
    border-color: #ff4d4f;
    background: #fff1f0;
}
.mtl-auth-bar__btn {
    padding: 6px 16px;
    border: none;
    border-radius: 6px;
    background: #fa8c16;
    color: white;
    font-size: 14px;
    cursor: pointer;
    transition: transform 0.15s, box-shadow 0.15s;
}
.mtl-auth-bar__btn:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 3px 10px rgba(250, 140, 22, 0.4);
}
.mtl-auth-bar__btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
}
.mtl-auth-bar__err {
    color: #ff4d4f;
    font-size: 13px;
    margin-left: 4px;
}
```

- [ ] **Step 2: Verify CSS parses**

The project does not have a CSS linter wired up. Open the running sample app and confirm the existing layout still renders correctly (no accidental class collisions). Visual check: topbar and tabs look the same as before.

- [ ] **Step 3: Commit**

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git add methodTraceLog/src/main/resources/META-INF/resources/static/panel.css
git commit -m "feat(panel): add styles for top auth banner"
```

---

## Task 3: Refactor mtlAuth.js — Replace Modal with Banner

**Files:**
- Modify: `methodTraceLog/src/main/resources/META-INF/resources/static/mtlAuth.js` (full rewrite)

This is the largest change. The file becomes a banner-renderer plus an auth-fetch wrapper. The public API surface is preserved where possible.

- [ ] **Step 1: Replace the entire mtlAuth.js contents**

Write the new file. The shape:

```javascript
/* mtlAuth.js
 *
 * methodTraceLog 浏览器端鉴权帮手。
 *
 * 设计目标：
 *  - mtlCheckAuth() 是纯查询：返回 {authEnabled, sessionValid}，不开 UI
 *  - mtlMountAuthBanner(containerId, onSuccess) 把横幅渲染到指定容器
 *  - mtlUnmountAuthBanner(containerId) 隐藏并移除横幅 DOM
 *  - mtlFetch(url, options) 401 时若已挂横幅则抑制(避免 toast 噪音)，否则抛错
 *  - 登录成功后派发 mtlAuthLoginSucceeded 事件，mtlFetch 内部用它重试 pending 请求
 *  - 注销时调 /logout 然后重新挂横幅(由 panel.js 提供的注册回调)
 */
(function () {
    'use strict';

    if (window.__mtlAuthInjected) return;
    window.__mtlAuthInjected = true;

    // ============== 状态 ==============
    // 当前挂载的横幅 slot + 回调;同一时刻只允许一个
    let activeSlot = null;          // { container: HTMLElement, onSuccess: fn }
    let pendingRequests = [];       // mtlFetch 内部 401 时缓存的待重试请求
    const styleInjected = { value: false };

    // ============== 样式注入 ==============
    const css = `
.mtl-auth-bar {
    display: flex; align-items: center; gap: 12px;
    padding: 10px 24px;
    background: linear-gradient(90deg, #fff7e6 0%, #ffe7ba 100%);
    border-bottom: 1px solid #ffd591;
    color: #874d00; font-size: 14px;
    transition: transform 0.2s ease, opacity 0.2s ease;
}
.mtl-auth-bar.is-hiding { transform: translateY(-100%); opacity: 0; }
.mtl-auth-bar__icon { font-size: 18px; }
.mtl-auth-bar__input {
    flex: 1; max-width: 360px;
    padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px;
    font-size: 14px; outline: none;
    transition: border-color 0.15s, background 0.15s;
}
.mtl-auth-bar__input:focus { border-color: #fa8c16; }
.mtl-auth-bar__input.is-error { border-color: #ff4d4f; background: #fff1f0; }
.mtl-auth-bar__btn {
    padding: 6px 16px; border: none; border-radius: 6px;
    background: #fa8c16; color: white; font-size: 14px; cursor: pointer;
    transition: transform 0.15s, box-shadow 0.15s;
}
.mtl-auth-bar__btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 3px 10px rgba(250,140,22,0.4); }
.mtl-auth-bar__btn:disabled { opacity: 0.6; cursor: not-allowed; }
.mtl-auth-bar__err { color: #ff4d4f; font-size: 13px; margin-left: 4px; }
.mtl-logout-btn {
    background: rgba(255, 255, 255, 0.85); color: #555;
    border: 1px solid rgba(102, 126, 234, 0.3);
    padding: 6px 14px; border-radius: 8px;
    cursor: pointer; font-size: 13px;
}
.mtl-logout-btn:hover { background: white; }
`;
    function ensureStyle() {
        if (styleInjected.value) return;
        const doInject = () => {
            if (styleInjected.value) return;
            const el = document.createElement('style');
            el.textContent = css;
            document.head.appendChild(el);
            styleInjected.value = true;
        };
        if (document.head) doInject();
        else document.addEventListener('DOMContentLoaded', doInject, { once: true });
    }

    // ============== 纯查询：不弹任何 UI ==============
    window.mtlCheckAuth = function () {
        return fetch('/methodTraceLog/session/status', { credentials: 'same-origin' })
            .then(r => r.ok ? r.json() : { authEnabled: false, sessionValid: false })
            .catch(() => ({ authEnabled: false, sessionValid: false }));
    };

    // ============== 挂载 / 卸载横幅 ==============
    function renderBanner(container) {
        container.innerHTML = `
            <div class="mtl-auth-bar" role="alert">
                <span class="mtl-auth-bar__icon">🔑</span>
                <label for="mtlAuthKey">请输入 API Key</label>
                <input id="mtlAuthKey" class="mtl-auth-bar__input" type="password"
                       placeholder="X-Api-Key" autocomplete="off" />
                <button id="mtlAuthSubmit" class="mtl-auth-bar__btn">登录</button>
                <span id="mtlAuthErr" class="mtl-auth-bar__err" hidden></span>
            </div>
        `;
        const $key = container.querySelector('#mtlAuthKey');
        const $btn = container.querySelector('#mtlAuthSubmit');
        const $err = container.querySelector('#mtlAuthErr');

        function showErr(msg) {
            $err.textContent = msg;
            $err.hidden = false;
            $key.classList.add('is-error');
        }
        function clearErr() {
            $err.textContent = '';
            $err.hidden = true;
            $key.classList.remove('is-error');
        }

        async function submit() {
            const key = $key.value;
            clearErr();
            if (!key) { showErr('请输入 API Key'); return; }
            $btn.disabled = true;
            $btn.textContent = '登录中…';
            try {
                const r = await fetch('/methodTraceLog/login', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'X-Api-Key': key }
                });
                if (r.status === 200) {
                    // 派发事件：mtlFetch 内部用它重试 pending 请求
                    try { window.dispatchEvent(new CustomEvent('mtlAuthLoginSucceeded')); } catch (e) { /* ignore */ }
                    // 调用挂载时提供的 onSuccess(由 panel.js 决定如何加载数据)
                    if (activeSlot && typeof activeSlot.onSuccess === 'function') {
                        try { activeSlot.onSuccess(); } catch (e) { console.error('onSuccess error', e); }
                    }
                } else {
                    showErr('❌ API Key 无效或鉴权未启用');
                    $key.select();
                }
            } catch (e) {
                showErr('❌ 网络异常：' + e.message);
            } finally {
                $btn.disabled = false;
                $btn.textContent = '登录';
            }
        }

        $btn.addEventListener('click', submit);
        $key.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
        // 自动聚焦
        setTimeout(() => $key.focus(), 50);
    }

    window.mtlMountAuthBanner = function (containerId, onSuccess) {
        ensureStyle();
        const container = document.getElementById(containerId);
        if (!container) {
            console.warn('[mtlAuth] container #' + containerId + ' not found');
            return;
        }
        // 同一 slot 重复挂载：保留最新回调
        activeSlot = { container, onSuccess };
        renderBanner(container);
    };

    window.mtlUnmountAuthBanner = function (containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;
        const bar = container.querySelector('.mtl-auth-bar');
        if (bar) {
            bar.classList.add('is-hiding');
            setTimeout(() => {
                container.innerHTML = '';
                if (activeSlot && activeSlot.container === container) {
                    activeSlot = null;
                }
            }, 220);
        } else {
            container.innerHTML = '';
            if (activeSlot && activeSlot.container === container) {
                activeSlot = null;
            }
        }
    };

    // ============== 注销：调 /logout，然后重新挂横幅 ==============
    window.mtlLogout = function () {
        fetch('/methodTraceLog/logout', { method: 'POST', credentials: 'same-origin' })
            .finally(() => {
                // 重试 pending 请求不会成功(没 session)，先清掉避免泄漏
                pendingRequests = [];
                // 重新挂横幅：调用 activeSlot.onSuccess 之外没有现成 API，
                // 这里直接由 panel.js 在挂载时同时注册 onLoggedOut 来处理；
                // 简化做法：派发 mtlAuthLoggedOut 事件，panel.js 监听并重挂横幅。
                try { window.dispatchEvent(new CustomEvent('mtlAuthLoggedOut')); } catch (e) { /* ignore */ }
            });
    };

    // ============== 登出事件：panel.js 监听此事件以重新挂横幅 ==============
    // (本文件不直接处理；提供事件给调用方挂载横幅)

    // ============== 401 重试：监听 mtlAuthLoginSucceeded 后排空队列 ==============
    function flushPending() {
        const list = pendingRequests;
        pendingRequests = [];
        list.forEach(p => p.retry());
    }
    window.addEventListener('mtlAuthLoginSucceeded', flushPending);

    // ============== mtlFetch：401 行为 ==============
    // - 若已挂横幅：把请求放入 pending 队列，登录成功后由 flushPending 重试；不抛错(避免 tab toast 噪音)
    // - 若未挂横幅：抛 Error('unauthorized') 给调用方 .catch
    window.mtlFetch = function (url, options) {
        options = options || {};
        if (!options.credentials) options.credentials = 'same-origin';
        const tryOnce = () => fetch(url, options);
        return new Promise((resolve, reject) => {
            tryOnce().then(r => {
                if (r.status === 401 && !url.includes('/methodTraceLog/login')) {
                    if (activeSlot) {
                        // 已挂横幅：缓存，等登录成功重试
                        pendingRequests.push({
                            resolve,
                            reject,
                            retry: () => tryOnce().then(resolve, reject)
                        });
                    } else {
                        // 未挂横幅：抛错
                        reject(new Error('unauthorized'));
                    }
                } else {
                    resolve(r);
                }
            }, reject);
        });
    };

    // ============== 登出按钮(供 panel.js 调用) ==============
    window.mtlRenderLogout = function (containerId) {
        const c = document.getElementById(containerId);
        if (!c) return;
        const btn = document.createElement('button');
        btn.className = 'mtl-logout-btn';
        btn.textContent = '🚪 注销';
        btn.title = '清除当前 session，下次访问会要求重新输入 API Key';
        btn.addEventListener('click', () => {
            if (confirm('确定要注销当前会话？注销后需要重新输入 API Key。')) {
                window.mtlLogout();
            }
        });
        c.appendChild(btn);
    };
})();
```

The file is now ~190 lines (down from the original ~340). Behavior changes:

- `mtlCheckAuth` is a pure one-shot query (no polling, no UI side effects).
- `mtlMountAuthBanner(id, onSuccess)` renders the banner; `onSuccess` is invoked after a successful `/login`.
- `mtlUnmountAuthBanner(id)` removes the banner with a slide-up animation.
- `mtlFetch` returns a Promise that, on 401:
  - if a banner is mounted → silently queue and retry on `mtlAuthLoginSucceeded`
  - if no banner → reject with `Error('unauthorized')`
- `mtlLogout` posts to `/logout` then dispatches `mtlAuthLoggedOut` (a new event; panel.js listens to it to re-mount the banner).
- `mtlAuthLoginSucceeded` event is still dispatched on successful login; flushes the pending queue.

- [ ] **Step 2: Verify the new mtlAuth.js parses**

There is no JS linter wired up in the project. Manual verification: run the sample app, open DevTools console, paste `typeof window.mtlCheckAuth === 'function'` and confirm it prints `true`. Repeat for `mtlMountAuthBanner`, `mtlUnmountAuthBanner`, `mtlLogout`, `mtlFetch`, `mtlRenderLogout`.

- [ ] **Step 3: Verify the file size shrank**

```bash
wc -l methodTraceLog/src/main/resources/META-INF/resources/static/mtlAuth.js
```

Expected: around 190 lines (was ~340). Useful sanity check that the rewrite actually happened.

- [ ] **Step 4: Commit**

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git add methodTraceLog/src/main/resources/META-INF/resources/static/mtlAuth.js
git commit -m "refactor(panel-auth): replace modal with banner, simplify mtlAuth API"
```

---

## Task 4: Refactor panel.js — Decouple Tab Render from Auth

**Files:**
- Modify: `methodTraceLog/src/main/resources/META-INF/resources/static/panel.js` (full rewrite)

- [ ] **Step 1: Replace the entire panel.js contents**

```javascript
/* panel.js
 *
 * 入口 + tab 路由。
 *  - DOMContentLoaded:
 *      1) 挂登出按钮
 *      2) 监听 mtlAuthLoggedOut → 重新挂横幅(注销后)
 *      3) showTab('overview', {skipOnShow:true}) 渲染骨架
 *      4) mtlCheckAuth().then(state):
 *           - 需登录: mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess)
 *           - 不用登录: loadCurrentTabData()
 *  - showTab(name, {skipOnShow}): 切显示 + 通知 tab 模块 onShow(可选)
 *  - 4 个 tab 模块通过 window.MTL.registerTab(name, {onShow, onHide}) 注册
 *  - Toast: MTL.toast(msg)
 *  - Modal: mtlOpenTraceModal(traceid) / mtlCloseModal()
 */
(function () {
    'use strict';

    const TABS = ['overview', 'traces', 'logs', 'decompile'];
    const tabModules = {};   // name -> {onShow?, onHide?}
    let currentTab = null;

    function registerTab(name, mod) {
        if (!TABS.includes(name)) return;
        tabModules[name] = mod || {};
    }

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

    function mtlToast(message) {
        const toast = document.getElementById('toast');
        if (!toast) return;
        toast.innerHTML = message;
        toast.className = 'show';
        setTimeout(() => { toast.className = toast.className.replace('show', ''); }, 3000);
    }

    let modalEl = null;
    function ensureModal() {
        if (modalEl) return modalEl;
        modalEl = document.getElementById('modal');
        const closeBtn = document.getElementById('modalCloseBtn');
        if (closeBtn) closeBtn.addEventListener('click', mtlCloseModal);
        if (modalEl) {
            modalEl.addEventListener('click', e => {
                if (e.target === modalEl) mtlCloseModal();
            });
        }
        return modalEl;
    }
    function mtlOpenModal() { ensureModal().style.display = 'block'; }
    function mtlCloseModal() { const m = ensureModal(); if (m) m.style.display = 'none'; }

    function mtlFormatFileSize(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
    function mtlFormatDate(timestamp) { return new Date(timestamp).toLocaleDateString('zh-CN'); }
    function mtlFormatDateTime(dateTimeStr) { return new Date(dateTimeStr).toLocaleString('zh-CN'); }
    function mtlEscapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function onHashChange() {
        const name = (location.hash || '').replace('#', '') || 'overview';
        showTab(name);
    }

    // 显式触发当前 tab 的数据加载
    function loadCurrentTabData() {
        const mod = currentTab && tabModules[currentTab];
        if (mod && mod.onShow) {
            try { mod.onShow(); } catch (e) { console.error(e); }
        }
    }

    // 横幅登录成功后的回调：隐藏横幅 + 拉数据
    function onBannerSuccess() {
        if (window.mtlUnmountAuthBanner) {
            window.mtlUnmountAuthBanner('mtlAuthSlot');
        }
        loadCurrentTabData();
    }

    // 注销后重新挂横幅(由 mtlAuth.js 派发的 mtlAuthLoggedOut 事件触发)
    function onLoggedOut() {
        if (window.mtlMountAuthBanner) {
            window.mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (window.mtlRenderLogout) mtlRenderLogout('mtlLogoutSlot');

        // 注销事件：重新挂横幅
        window.addEventListener('mtlAuthLoggedOut', onLoggedOut);

        // 1) 立即渲染 tab 骨架(不调 onShow,等鉴权决定)
        showTab('overview', { skipOnShow: true });
        window.addEventListener('hashchange', onHashChange);

        // 2) 检查登录态,决定挂横幅 / 拉数据
        if (window.mtlCheckAuth) {
            window.mtlCheckAuth().then(state => {
                if (state.authEnabled && !state.sessionValid) {
                    if (window.mtlMountAuthBanner) {
                        window.mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess);
                    }
                } else {
                    loadCurrentTabData();
                }
            });
        } else {
            loadCurrentTabData();
        }
    });

    window.MTL = {
        registerTab,
        showTab,
        toast: mtlToast,
        openModal: mtlOpenModal,
        closeModal: mtlCloseModal,
        formatFileSize: mtlFormatFileSize,
        formatDate: mtlFormatDate,
        formatDateTime: mtlFormatDateTime,
        escapeHtml: mtlEscapeHtml
    };
})();
```

Key changes vs. the original:

- `showTab(name)` → `showTab(name, opts)`. When `opts.skipOnShow` is true, the onShow callback is skipped. Default behavior (no opts) is unchanged → tab switches via `onHashChange` still auto-load data.
- `DOMContentLoaded` calls `showTab('overview', { skipOnShow: true })` so the page skeleton renders immediately without firing network requests.
- `loadCurrentTabData()` is a small helper that invokes the current tab's onShow.
- `onBannerSuccess` is the callback passed to `mtlMountAuthBanner`. It hides the banner, then loads data.
- `onLoggedOut` is wired to the new `mtlAuthLoggedOut` event dispatched by `mtlLogout`. It re-mounts the banner.

- [ ] **Step 2: Verify panel.js parses**

There is no JS linter in the project. Manual verification: load `/methodTraceLog/panel` in the browser with the dev server running, open DevTools console, and run:

```js
typeof window.MTL.registerTab === 'function' && typeof window.MTL.showTab === 'function'
```

Expected: `true`.

- [ ] **Step 3: Commit**

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git add methodTraceLog/src/main/resources/META-INF/resources/static/panel.js
git commit -m "refactor(panel): decouple tab render from auth check, add skipOnShow"
```

---

## Task 5: Manual Browser Verification

**Files:** none (verification only)

- [ ] **Step 1: Start the sample app**

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog-test -am package -DskipTests
java -cp methodTraceLog-test/target/classes:methodTraceLog-test/target/test-classes:methodTraceLog/target/classes:methodTraceLog-spring-boot-autoconfigure/target/classes:$(/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog-test -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null) cn.wubo.method.trace.log.TestApplication
```

(Use the existing launch command the developer prefers; the project's CLAUDE.md mentions `mvn -pl methodTraceLog-test spring-boot:run` but the test module doesn't declare the spring-boot-maven-plugin — use `java -cp ...` or adjust.)

Default port: 8088. If the developer's `application.yml` has a different port, use that.

- [ ] **Step 2: Scenario 1 — no auth required**

1. Ensure `method-trace-log.security.api-key` is empty (or not set) in `methodTraceLog-test/src/main/resources/application.yml`.
2. Open `http://localhost:8088/methodTraceLog/panel#overview` in a fresh browser window (or in incognito).
3. Expected: page loads immediately, no banner, data populates within a few hundred ms.

- [ ] **Step 3: Scenario 2 — first access, needs login**

1. Set `method-trace-log.security.api-key: test123` in `application.yml`, restart the app.
2. In the browser, clear cookies for `localhost:8088` (DevTools → Application → Cookies → right-click → Clear).
3. Open `http://localhost:8088/methodTraceLog/panel#overview`.
4. Expected:
   - Page skeleton (topbar + 4 tab buttons) renders immediately.
   - Yellow banner appears between topbar and main, with input + "登录" button.
   - Data area is empty / shows spinner.
5. Click "登录" with the input empty. Expected: input gets red border, "请输入 API Key" appears next to it. No network request fires.
6. Type `wrong` and click "登录". Expected: input red border, "❌ API Key 无效或鉴权未启用" appears.
7. Type `test123` and click "登录". Expected: banner slides up and disappears; data loads (cards populate, table rows appear) within ~1s.
8. **Critical check**: no manual refresh should be required — the page becomes usable as soon as the banner hides.

- [ ] **Step 4: Scenario 3 — refresh within TTL**

1. From Scenario 2, while logged in, press F5 / refresh the page.
2. Expected: page loads, no banner appears, data populates normally.

- [ ] **Step 5: Scenario 4 — explicit logout**

1. Click the "🚪注销" button in the topbar.
2. Confirm the dialog.
3. Expected:
   - Banner re-appears at the top.
   - Data area empties (no rows).
4. Type a wrong key. Expected: red border + error.
5. Type the correct key. Expected: banner disappears, data loads.

- [ ] **Step 6: Scenario 5 — multi-tab navigation while logged out**

1. Logout (or clear cookies) so the banner is shown.
2. Click each of the 4 tab buttons (概览 / 调用记录 / 日志文件 / 反编译).
3. Expected: tab panels switch, no 401 toast appears, no errors in DevTools console. Each tab shows its own skeleton / search form.

- [ ] **Step 7: Scenario 6 — Enter key submits the banner**

1. Logged out, banner visible.
2. Type the correct key in the banner input, press Enter (without clicking the button).
3. Expected: same as clicking "登录" — banner disappears, data loads.

- [ ] **Step 8: Scenario 7 — pass through `mtlFetch` 401 while logged out**

1. Logged out, banner visible.
2. Open DevTools console and run:
   ```js
   mtlFetch('/methodTraceLog/view/list').then(r => console.log('ok', r.status)).catch(e => console.log('err', e.message))
   ```
3. Expected: `ok 200` (the request gets queued, login happens implicitly when the user logs in via banner). Or, if no banner is mounted (test by calling `mtlUnmountAuthBanner('mtlAuthSlot')` first), expected: `err unauthorized`.

- [ ] **Step 9: Commit verification notes (optional)**

If any issues were found and fixed, commit those fixes. If everything passes, no commit needed.

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git status
# If clean, nothing to commit. If dirty, git diff to review and commit.
```

- [ ] **Step 10: Final summary commit (if applicable)**

If the verification surfaced tweaks (e.g., timing of the slide-up animation, copy edits, default focus behavior), commit them as a follow-up:

```bash
cd "C:/developer/IdeaProjects/methodTraceLog"
git add -A
git commit -m "fix(panel-auth): verification-driven tweaks from manual test"
```

---

## Self-Review Notes

**Spec coverage check (against `2026-06-10-panel-auth-banner-design.md`):**

| Spec section | Covered by |
|--------------|-----------|
| §问题 1 (post-login empty page) | Task 4 (skipOnShow + onBannerSuccess) |
| §问题 2 (modal UX) | Task 3 (replace modal with banner) |
| §设计目标 1 (skeleton immediate) | Task 4 (showTab with skipOnShow) |
| §设计目标 2 (auth/render decoupled) | Task 4 (loadCurrentTabData explicit) |
| §设计目标 3 (single source of truth) | Task 4 (panel.js explicit control) |
| §设计目标 4 (no backend changes) | n/a — confirmed by file list |
| §设计目标 5 (no-key mode works) | Task 5 Scenario 1 |
| §设计目标 6 (no localStorage) | Task 3 (no localStorage references) |
| §设计目标 7 (session in TTL) | Task 5 Scenario 3 |
| §UI 布局 (banner above main) | Task 1 (slot HTML), Task 2 (CSS) |
| §时序 (load + submit flow) | Task 3 (mtlCheckAuth, renderBanner.submit), Task 4 (entry) |
| §API 改动表格 | Task 3 (mtlAuth.js rewrite), Task 4 (panel.js rewrite) |
| §数据流图 | Tasks 3+4 implement it |
| §错误处理表 | Task 5 verifies each row |
| §兼容性 (mtlFetch 401 suppression) | Task 3 (mtlFetch new behavior) |
| §不在范围内 (no localStorage, no TTL change, no MCP changes) | Confirmed: no edits to those areas |
| §验收标准 9 条 | All covered by Task 5 scenarios |

**No placeholders:** all steps have concrete code or commands.

**Type/name consistency:** the new `mtlMountAuthBanner(id, onSuccess)` is called with the same signature from `panel.js` (`onBannerSuccess` is the `onSuccess`). `mtlUnmountAuthBanner(id)` is called with the slot id `'mtlAuthSlot'`. `mtlAuthLoggedOut` event is dispatched by `mtlLogout` and listened to by `panel.onLoggedOut`. `mtlAuthLoginSucceeded` event is dispatched by `mtlAuth.js` on login success and listened to by `mtlAuth.js` itself for queue flushing. All consistent.

**Out-of-scope guard:** Tasks 1-4 touch only the four files listed in §文件改动清单 of the spec. Task 5 is verification only.
