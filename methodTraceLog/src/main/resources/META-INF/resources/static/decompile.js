/* decompile.js — 反编译 tab
 *  - 调 GET /methodTraceLog/decompile?className=&methodName=&timeoutSeconds=
 *  - text/plain 放进 <pre id="decResult">
 */
(function () {
    'use strict';

    function submit() {
        const cn = document.getElementById('decClassName').value.trim();
        const mn = document.getElementById('decMethodName').value.trim();
        const to = parseInt(document.getElementById('decTimeout').value) || 10;
        if (!cn || !mn) { MTL.toast('请填写类全名和方法名'); return; }

        const out = document.getElementById('decResult');
        out.textContent = '正在反编译…';
        const btn = document.getElementById('decSubmitBtn');
        btn.disabled = true;

        const qs = new URLSearchParams({ className: cn, methodName: mn, timeoutSeconds: to });
        mtlFetch('/methodTraceLog/decompile?' + qs.toString())
            .then(r => {
                if (!r.ok) return r.text().then(t => { throw new Error(t || ('HTTP ' + r.status)); });
                return r.text();
            })
            .then(src => { out.textContent = src || '// 空结果'; })
            .catch(e => { out.textContent = '// 反编译失败：' + e.message; MTL.toast(e.message); })
            .finally(() => { btn.disabled = false; });
    }

    function clear() {
        document.getElementById('decClassName').value = '';
        document.getElementById('decMethodName').value = '';
        document.getElementById('decTimeout').value = 10;
        document.getElementById('decResult').textContent = '// 结果会显示在这里';
    }

    function onShow() {
        const sub = document.getElementById('decSubmitBtn');
        const clr = document.getElementById('decClearBtn');
        if (sub && !sub._mtlBound) { sub.addEventListener('click', submit); sub._mtlBound = true; }
        if (clr && !clr._mtlBound) { clr.addEventListener('click', clear); clr._mtlBound = true; }
    }
    function onHide() { /* no state to clear */ }

    document.addEventListener('DOMContentLoaded', () => {
        MTL.registerTab('decompile', { onShow, onHide });
    });
})();
