/* traces.js — 调用记录 tab
 *  - 过滤: className / methodName / onlyErrors / limit
 *  - 导出: JSON / CSV（limit 默认 1000，与后端一致）
 *  - 表格行点击调 MTLTrace.openModal 显示调用链
 */
(function () {
    'use strict';

    function buildParams() {
        const cn  = document.getElementById('traceClassName').value.trim();
        const mn  = document.getElementById('traceMethodName').value.trim();
        const oe  = document.getElementById('traceOnlyErrors').value === 'true';
        const lim = document.getElementById('traceLimit').value;
        const qs = new URLSearchParams();
        if (cn) qs.set('className', cn);
        if (mn) qs.set('methodName', mn);
        if (oe) qs.set('onlyErrors', 'true');
        qs.set('limit', lim);
        return qs.toString();
    }

    function renderEmpty(msg) {
        MTL.renderEmpty('tracesTable', { title: msg, hint: '试试调整类名/方法名过滤条件', icon: 'search-x' });
    }

    function query() {
        const table = document.getElementById('tracesTable');
        table.innerHTML = '<div class="loading"><div class="spinner"></div><p>正在加载…</p></div>';
        mtlFetch('/methodTraceLog/view/list?' + buildParams())
            .then(r => r.json())
            .then(data => {
                if (!data || data.length === 0) { renderEmpty('未匹配到任何调用记录'); return; }
                const title = document.getElementById('traceTableTitle');
                title.textContent = `调用记录（共 ${data.length} 条）`;
                table.innerHTML = window.MTLTrace.renderTraceRows(data);
            })
            .catch(e => { MTL.toast(e.message); renderEmpty('查询失败'); });
    }

    function exportFile(format) {
        const url = '/methodTraceLog/view/export?format=' + format + '&' + buildParams();
        MTL.toast((window.mtlIconHTML ? window.mtlIconHTML('arrow-down', '正在准备 ' + format.toUpperCase() + ' 下载…') : '正在准备 ' + format.toUpperCase() + ' 下载…'));
        // 走 mtlFetch 以保证鉴权，然后手动触发下载
        mtlFetch(url)
            .then(r => {
                if (!r.ok) throw new Error('导出失败 HTTP ' + r.status);
                const cd = r.headers.get('content-disposition') || '';
                const m = cd.match(/filename="?([^";]+)"?/);
                const filename = m ? m[1] : ('method-traces.' + format);
                return r.blob().then(blob => ({ blob, filename }));
            })
            .then(({ blob, filename }) => {
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(a.href);
            })
            .catch(e => MTL.toast(e.message));
    }

    function reset() {
        document.getElementById('traceClassName').value = '';
        document.getElementById('traceMethodName').value = '';
        document.getElementById('traceOnlyErrors').value = 'false';
        document.getElementById('traceLimit').value = '1000';
        document.getElementById('traceTableTitle').textContent = '调用记录';
        renderEmpty('请输入过滤条件后点击「查询」');
    }

    function onShow() {
        const map = [
            ['traceSearchBtn', 'click', query],
            ['traceResetBtn',  'click', reset],
            ['traceExportJson','click', () => exportFile('json')],
            ['traceExportCsv', 'click', () => exportFile('csv')]
        ];
        map.forEach(([id, evt, fn]) => {
            const el = document.getElementById(id);
            if (el && !el._mtlBound) { el.addEventListener(evt, fn); el._mtlBound = true; }
        });
        // 首次进入自动查一次(同样的数据源,只是这里能过滤 / 改 limit / 导出)。
        // 后续切回 tab 复用上一次的查询结果,不重发请求(用户已经调过的过滤条件不丢)。
        if (!window._mtlTracesFirstShown) {
            window._mtlTracesFirstShown = true;
            query();
        }
    }
    function onHide() { /* no auto-refresh on this tab */ }

    document.addEventListener('DOMContentLoaded', () => {
        MTL.registerTab('traces', { onShow, onHide });
    });
})();
