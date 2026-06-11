/* decompile.js — 反编译 tab
 *  - 调 GET /methodTraceLog/decompile?className=&methodName=&timeoutSeconds=
 *  - 顶部 toolbar:类全名 + 行数 + 复制按钮
 *  - 主体:左侧行号(aria-hidden) + 右侧反编译输出,共用横向滚动
 */
(function () {
    'use strict';

    function setMeta(text, state) {
        // state: 'idle' | 'loading' | 'ok' | 'error'
        const meta = document.getElementById('decMeta');
        const copyBtn = document.getElementById('decCopyBtn');
        if (meta) meta.textContent = text;
        if (copyBtn) copyBtn.disabled = (state !== 'ok');
    }

    function renderLinenums(text) {
        const el = document.getElementById('decLinenums');
        if (!el) return;
        if (!text) { el.textContent = ''; return; }
        const count = text.split('\n').length;
        // "1\n2\n3\n..." 形式,pre 配合 white-space:pre 渲染成竖排行号
        let s = '';
        for (let i = 1; i <= count; i++) s += i + '\n';
        el.textContent = s;
    }

    function submit() {
        const cn = document.getElementById('decClassName').value.trim();
        const mn = document.getElementById('decMethodName').value.trim();
        const to = parseInt(document.getElementById('decTimeout').value) || 10;
        if (!cn || !mn) { MTL.toast('请填写类全名和方法名'); return; }

        const out = document.getElementById('decResult');
        out.textContent = '// 正在反编译…';
        renderLinenums('');
        setMeta('正在反编译 ' + cn + '#' + mn + ' …', 'loading');
        const btn = document.getElementById('decSubmitBtn');
        btn.disabled = true;

        const qs = new URLSearchParams({ className: cn, methodName: mn, timeoutSeconds: to });
        mtlFetch('/methodTraceLog/decompile?' + qs.toString())
            .then(r => {
                if (!r.ok) return r.text().then(t => { throw new Error(t || ('HTTP ' + r.status)); });
                return r.text();
            })
            .then(src => {
                const body = src || '// 空结果';
                out.textContent = body;
                renderLinenums(body);
                const lines = body.split('\n').length;
                const bytes = new Blob([body]).size;
                setMeta(cn + '#' + mn + '  ·  ' + lines + ' 行  ·  ' + MTL.formatFileSize(bytes), 'ok');
            })
            .catch(e => {
                out.textContent = '// 反编译失败：' + e.message;
                renderLinenums('');
                setMeta('反编译失败', 'error');
                MTL.toast(e.message);
            })
            .finally(() => { btn.disabled = false; });
    }

    function clear() {
        document.getElementById('decClassName').value = '';
        document.getElementById('decMethodName').value = '';
        document.getElementById('decTimeout').value = 10;
        document.getElementById('decResult').textContent = '// 结果会显示在这里';
        renderLinenums('');
        setMeta('等待结果', 'idle');
        const copyBtn = document.getElementById('decCopyBtn');
        if (copyBtn) { copyBtn.disabled = true; copyBtn.classList.remove('copied'); }
    }

    function copyResult() {
        const out = document.getElementById('decResult');
        const btn = document.getElementById('decCopyBtn');
        const text = (out && out.textContent) || '';
        if (!text || text.startsWith('// ')) return;  // 占位 / 错误时不复制
        const fallback = () => {
            // 老浏览器 fallback
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed'; ta.style.opacity = '0';
            document.body.appendChild(ta); ta.select();
            try { document.execCommand('copy'); } catch (e) { /* ignore */ }
            document.body.removeChild(ta);
        };
        const onOk = () => {
            btn.classList.add('copied');
            const labelEl = btn.querySelector('.mtl-icon-text');
            const orig = labelEl ? labelEl.textContent : '';
            if (labelEl) labelEl.textContent = '已复制';
            const iconEl = btn.querySelector('[data-icon]');
            const oldIconName = iconEl ? iconEl.getAttribute('data-icon') : null;
            if (iconEl && window.mtlIcon) {
                iconEl.innerHTML = window.mtlIcon('check');
                iconEl.setAttribute('data-icon', 'check');
            }
            setTimeout(() => {
                btn.classList.remove('copied');
                if (labelEl) labelEl.textContent = orig;
                if (iconEl && oldIconName) {
                    iconEl.innerHTML = window.mtlIcon(oldIconName);
                    iconEl.setAttribute('data-icon', oldIconName);
                }
            }, 1600);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(onOk).catch(() => { fallback(); onOk(); });
        } else { fallback(); onOk(); }
    }

    function onShow() {
        const sub = document.getElementById('decSubmitBtn');
        const clr = document.getElementById('decClearBtn');
        const cpy = document.getElementById('decCopyBtn');
        if (sub && !sub._mtlBound) { sub.addEventListener('click', submit); sub._mtlBound = true; }
        if (clr && !clr._mtlBound) { clr.addEventListener('click', clear); clr._mtlBound = true; }
        if (cpy && !cpy._mtlBound) { cpy.addEventListener('click', copyResult); cpy._mtlBound = true; }
    }
    function onHide() { /* no state to clear */ }

    document.addEventListener('DOMContentLoaded', () => {
        MTL.registerTab('decompile', { onShow, onHide });
    });
})();
