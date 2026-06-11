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
 *  - Modal: MTL.openModal() / MTL.closeModal()(通用弹窗;调用链弹窗在 overview.js 的 window.MTLTrace 中)
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
        // 滑动指示器:等浏览器完成布局(0 双 RAF)再量,避免初次 hidden→visible 时取到 0 尺寸
        requestAnimationFrame(() => requestAnimationFrame(positionTabIndicator));
        // stagger 进入:每个 panel 进入时让直接子元素依次淡入
        document.querySelectorAll('.mtl-tab-panel').forEach(el => {
            if (el.dataset.tab === name) {
                el.classList.remove('mtl-stagger-on');
                // 强制 reflow 后重加,保证动画重放
                void el.offsetWidth;
                el.classList.add('mtl-stagger-on');
            } else {
                el.classList.remove('mtl-stagger-on');
            }
        });
        if (tabModules[name] && tabModules[name].onShow) {
            if (!opts || !opts.skipOnShow) {
                try { tabModules[name].onShow(); } catch (e) { console.error(e); }
            }
        }
    }

    // ============== 顶栏 tab 滑动指示器 ==============
    // 位置 + 宽度根据当前 active tab 实时算出,放到 .mtl-tab-indicator 上
    // 初始 .mtl-tab-indicator width=0 + opacity=0,首次定位后由 [data-has-active=true] 渐显
    function positionTabIndicator() {
        const tabsEl = document.getElementById('mtlTabs');
        const indicator = document.getElementById('mtlTabIndicator');
        if (!tabsEl || !indicator) return;
        const active = tabsEl.querySelector('.mtl-tab.active');
        if (!active) { tabsEl.removeAttribute('data-has-active'); return; }
        const tabRect = active.getBoundingClientRect();
        const tabsRect = tabsEl.getBoundingClientRect();
        // 容器有 padding 时,bRect 含 padding,需减掉
        const cs = getComputedStyle(tabsEl);
        const padL = parseFloat(cs.paddingLeft) || 0;
        const padT = parseFloat(cs.paddingTop) || 0;
        indicator.style.left = (tabRect.left - tabsRect.left - tabsEl.scrollLeft + padL) + 'px';
        indicator.style.width = tabRect.width + 'px';
        indicator.style.bottom = (tabsRect.height - tabRect.bottom + tabsRect.top - padT) + 'px';
        tabsEl.setAttribute('data-has-active', 'true');
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
    function mtlOpenModal() { ensureModal().style.display = 'flex'; }
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

    // ============== 空状态组件 ==============
    // 用法:MTL.renderEmpty(target, { title, hint, icon })
    //   target: HTMLElement 或 elementId
    //   title: 必填,主提示文字
    //   hint:  选填,副提示(灰色小字)
    //   icon:  选填,mtlIcons 注册的 icon 名(默认 'inbox')
    function mtlRenderEmpty(target, options) {
        const el = (typeof target === 'string') ? document.getElementById(target) : target;
        if (!el) return;
        const opts = options || {};
        const title = opts.title || '暂无数据';
        const hint  = opts.hint  || '';
        const icon  = opts.icon  || 'inbox';
        const iconHtml = (window.mtlIcon && window.MTL_ICONS && window.MTL_ICONS[icon])
            ? window.mtlIcon(icon) : '';
        el.innerHTML =
            '<div class="empty-state">' +
                '<div class="empty-state__icon">' + iconHtml + '</div>' +
                '<div class="empty-state__title">' + mtlEscapeHtml(title) + '</div>' +
                (hint ? '<div class="empty-state__hint">' + mtlEscapeHtml(hint) + '</div>' : '') +
            '</div>';
    }

    // ============== 数字计数动画 ==============
    // 用法:MTL.countUp(el, target, { duration, decimals, prefix, suffix, onStart, onDone })
    //   target: 数字 / 含前缀后缀的字符串("98.5%")/ null 走 toLocaleString
    //   duration: ms,默认 700
    //   decimals: 固定小数位,默认按 target 自动判(0.x 留 2 位,整数留 0)
    //   onStart(el): 触发 .mtl-counting 让数字变暗,onDone(el) 移除
    // 设计:rAF + easeOutCubic,中途中断(连续 refresh)就 cancel 上一帧
    function mtlCountUp(target, opts) {
        const o = opts || {};
        const el = (typeof target === 'string') ? document.getElementById(target) : target;
        if (!el) return;
        const to = o.to;
        if (to == null || isNaN(to)) { el.textContent = (o.fallback != null) ? o.fallback : '0'; return; }
        const duration = o.duration || 700;
        const decimals = (o.decimals != null) ? o.decimals : ((to % 1 !== 0) ? 2 : 0);
        const prefix = o.prefix || '';
        const suffix = o.suffix || '';
        const useGrouping = o.grouping !== false;  // 默认加千分位
        const from = 0;
        if (o.onStart) o.onStart(el);
        const start = performance.now();
        let cancelled = false;
        el._mtlCountCancel = () => { cancelled = true; };
        const ease = t => 1 - Math.pow(1 - t, 3);  // easeOutCubic
        const fmt = v => prefix + v.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals, useGrouping }) + suffix;
        function frame(now) {
            if (cancelled) return;
            const t = Math.min(1, (now - start) / duration);
            const val = from + (to - from) * ease(t);
            el.textContent = fmt(val);
            if (t < 1) requestAnimationFrame(frame);
            else { el._mtlCountCancel = null; if (o.onDone) o.onDone(el); }
        }
        requestAnimationFrame(frame);
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

    // 横幅登录成功后的回调：隐藏横幅 + 显示登出按钮 + 拉数据
    function onBannerSuccess() {
        if (window.mtlUnmountAuthBanner) {
            window.mtlUnmountAuthBanner('mtlAuthSlot');
        }
        if (window.mtlRenderLogout) {
            window.mtlRenderLogout('mtlLogoutSlot');
        }
        loadCurrentTabData();
    }

    // 注销后：隐藏登出按钮 + 重新挂横幅(由 mtlAuth.js 派发的 mtlAuthLoggedOut 事件触发)
    function onLoggedOut() {
        if (window.mtlHideLogout) {
            window.mtlHideLogout();
        }
        if (window.mtlMountAuthBanner) {
            window.mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess);
        }
    }

    // ============== 主题切换 ==============
    // 优先级:localStorage > 跟随系统。点 toggle 后写 localStorage,不再跟系统。
    const THEME_KEY = 'mtl-theme';
    function getStoredTheme() {
        try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; }
    }
    function storeTheme(theme) {
        try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* ignore */ }
    }
    function resolveInitialTheme() {
        const stored = getStoredTheme();
        if (stored === 'light' || stored === 'dark') return stored;
        return 'auto'; // 不显式设 data-theme,由 CSS 的 prefers-color-scheme 接管
    }
    function applyTheme(theme) {
        // theme = 'light' | 'dark' | 'auto'
        const root = document.documentElement;
        if (theme === 'auto') {
            root.removeAttribute('data-theme');
        } else {
            root.setAttribute('data-theme', theme);
        }
        // 同步 toggle 按钮图标:dark 时显示太阳(暗示"点我变亮"),light 时显示月亮
        const btn = document.getElementById('mtlThemeToggle');
        if (btn) {
            const effective = (theme === 'auto')
                ? (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
                : theme;
            btn.innerHTML = window.mtlIcon ? window.mtlIcon(effective === 'dark' ? 'sun' : 'moon') : '';
        }
    }
    function initTheme() {
        applyTheme(resolveInitialTheme());
        const btn = document.getElementById('mtlThemeToggle');
        if (btn) {
            btn.addEventListener('click', function () {
                const current = resolveInitialTheme();
                const root = document.documentElement;
                const effectiveDark = (root.getAttribute('data-theme') === 'dark')
                    || (current === 'auto' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
                const next = effectiveDark ? 'light' : 'dark';
                storeTheme(next);
                applyTheme(next);
            });
        }
        // 跟系统:用户没显式选过时,系统主题变化实时生效
        if (window.matchMedia) {
            const mq = window.matchMedia('(prefers-color-scheme: dark)');
            const onSystemChange = function () {
                if (getStoredTheme() === null) applyTheme('auto');
            };
            if (mq.addEventListener) mq.addEventListener('change', onSystemChange);
            else if (mq.addListener) mq.addListener(onSystemChange);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        // 主题:尽早设,避免初始 flash
        initTheme();

        // 展开所有 [data-icon] 占位符为 SVG
        if (window.mtlMountIcons) window.mtlMountIcons();

        // 注销事件：隐藏登出按钮 + 重新挂横幅
        window.addEventListener('mtlAuthLoggedOut', onLoggedOut);

        // 1) 立即渲染 tab 骨架(不调 onShow,等鉴权决定)
        //    初始 tab 优先看 URL hash,这样深链 panel#traces 能直接落到对应 tab。
        //    之前硬编码 'overview' 会被 showTab 里的 replaceState 反向冲掉 hash,
        //    而且 replaceState 不触发 hashchange,所以深链完全失效。
        const initialTab = (location.hash || '').replace('#', '');
        showTab(TABS.includes(initialTab) ? initialTab : 'overview', { skipOnShow: true });
        // webfont 加载完会改变字宽 → 重定位指示器
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(() => positionTabIndicator());
        }
        window.addEventListener('hashchange', onHashChange);
        window.addEventListener('resize', () => requestAnimationFrame(positionTabIndicator));

        // 2) 检查登录态,决定挂横幅 / 拉数据 / 显示登出按钮
        if (window.mtlCheckAuth) {
            window.mtlCheckAuth().then(state => {
                if (state.authEnabled && !state.sessionValid) {
                    if (window.mtlMountAuthBanner) {
                        window.mtlMountAuthBanner('mtlAuthSlot', onBannerSuccess);
                    }
                } else {
                    if (state.sessionValid && window.mtlRenderLogout) {
                        window.mtlRenderLogout('mtlLogoutSlot');
                    }
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
        escapeHtml: mtlEscapeHtml,
        renderEmpty: mtlRenderEmpty,
        countUp: mtlCountUp
    };
})();
