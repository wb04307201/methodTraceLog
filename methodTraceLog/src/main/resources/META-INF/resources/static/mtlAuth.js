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
        // 同一 slot 重复挂载：取消残留的卸载 timeout,避免它清掉新横幅的 DOM
        if (activeSlot && activeSlot.container === container && activeSlot.hideTimer) {
            clearTimeout(activeSlot.hideTimer);
        }
        // 同一 slot 重复挂载：保留最新回调
        activeSlot = { container, onSuccess, hideTimer: null };
        renderBanner(container);
    };

    window.mtlUnmountAuthBanner = function (containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;
        // 没有横幅 + 没有待清理 timeout:什么都不做
        if (!activeSlot || activeSlot.container !== container) {
            const bar = container.querySelector('.mtl-auth-bar');
            if (!bar) return;
        }
        const bar = container.querySelector('.mtl-auth-bar');
        if (bar) {
            bar.classList.add('is-hiding');
            const timer = setTimeout(() => {
                container.innerHTML = '';
                if (activeSlot && activeSlot.container === container) {
                    activeSlot = null;
                }
            }, 220);
            if (activeSlot && activeSlot.container === container) {
                activeSlot.hideTimer = timer;
            }
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
