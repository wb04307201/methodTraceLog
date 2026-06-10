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

    document.addEventListener('DOMContentLoaded', function () {
        // 注销事件：隐藏登出按钮 + 重新挂横幅
        window.addEventListener('mtlAuthLoggedOut', onLoggedOut);

        // 1) 立即渲染 tab 骨架(不调 onShow,等鉴权决定)
        showTab('overview', { skipOnShow: true });
        window.addEventListener('hashchange', onHashChange);

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
        escapeHtml: mtlEscapeHtml
    };
})();
