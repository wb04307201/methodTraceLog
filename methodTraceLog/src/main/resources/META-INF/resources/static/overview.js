/* overview.js — 概览 tab
 *  - 服务开关 / 摘要卡 / 刷新 / 两张表 / 弹窗调用链
 *  - 移自 view.js + viewCom.js
 *  - 用 MTL.toast / MTL.openModal 替代散落的 alert/showToast
 */
(function () {
    'use strict';

    const timeOptions = {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        fractionalSecondDigits: 3
    };

    let refreshIntervalId = null;

    function loadData() {
        mtlFetch('/methodTraceLog/view/callServices')
            .then(r => r.json())
            .then(updateCallServices)
            .catch(e => MTL.toast(e.message));

        mtlFetch('/actuator/methodtrace')
            .then(r => r.json())
            .then(data => { updateSummary(data); updateTable(data); })
            .catch(e => MTL.toast(e.message));

        mtlFetch('/methodTraceLog/view/list')
            .then(r => r.json())
            .then(updateMethodTable)
            .catch(e => MTL.toast(e.message));
    }

    function updateSummary(data) {
        let totalCount = 0, successCount = 0;
        data.forEach(item => { totalCount += item.totalCalls; successCount += item.successCalls; });
        const avgSuccessRate = totalCount > 0 ? ((successCount / totalCount) * 100) : 0;
        // 中断上一轮正在滚的数字,避免快速 auto-refresh 时数字来回跳
        const cancelPrev = el => { if (el && el._mtlCountCancel) el._mtlCountCancel(); };
        cancelPrev(document.getElementById('totalCount'));
        cancelPrev(document.getElementById('successCount'));
        cancelPrev(document.getElementById('avgSuccessRate'));
        MTL.countUp('totalCount',    { to: totalCount,     duration: 700, onStart: el => el.classList.add('mtl-counting'), onDone: el => el.classList.remove('mtl-counting') });
        MTL.countUp('successCount',  { to: successCount,   duration: 700, onStart: el => el.classList.add('mtl-counting'), onDone: el => el.classList.remove('mtl-counting') });
        MTL.countUp('avgSuccessRate',{ to: avgSuccessRate, duration: 700, decimals: 2, suffix: '%', onStart: el => el.classList.add('mtl-counting'), onDone: el => el.classList.remove('mtl-counting') });
    }

    function updateTable(data) {
        const el = document.getElementById('methodtrace');
        if (!data || data.length === 0) { MTL.renderEmpty(el, { title: '暂无统计数据', hint: '调用方法后这里会显示耗时与成功率' }); return; }
        let html = `<div class="table-wrapper"><table><thead><tr>
                <th>类名</th><th>方法名</th><th>调用</th><th>成功</th><th>成功率</th><th>耗时(ms)</th>
            </tr></thead><tbody>`;
        data.forEach(item => {
            const method = item.methodSignature.split(' ').pop().replace(item.className + '.', '');
            html += `<tr>
                <td>${item.className}</td>
                <td>${method}</td>
                <td>${item.totalCalls.toLocaleString()}</td>
                <td>${item.successCalls.toLocaleString()}</td>
                <td>${item.successRate.toFixed(2)}%</td>
                <td>${item.averageSuccessTime ? item.averageSuccessTime.toFixed(2) : 'N/A'}</td>
            </tr>`;
        });
        html += '</tbody></table></div>';
        el.innerHTML = html;
    }

    function updateMethodTable(data) {
        const el = document.getElementById('method');
        if (!data || data.length === 0) { MTL.renderEmpty(el, { title: '暂无调用记录', hint: '调用任何被追踪的方法后会出现在这里' }); return; }
        let html = `<div class="table-wrapper"><table><thead><tr>
                <th>类名</th><th>方法名</th><th>开始时间</th><th>结束时间</th><th>耗时(ms)</th><th>状态</th><th>链路</th>
            </tr></thead><tbody>`;
        data.forEach(item => {
            const b = item.before;
            const methodSig = b.methodSignatureLongString.split(' ').pop().replace(b.className + '.', '');
            const start = new Date(b.timeMillis).toLocaleString('zh-CN', timeOptions);
            const end = item.after ? new Date(item.after.timeMillis).toLocaleString('zh-CN', timeOptions) : 'N/A';
            const period = item.after ? (item.after.timeMillis - b.timeMillis) : 'N/A';
            const status = item.after
                ? (item.after.logActionEnum === 'AFTER_RETURN'
                    ? '<span class="mtl-status mtl-status--success"></span>成功'
                    : '<span class="mtl-status mtl-status--error"></span>失败')
                : '<span class="mtl-status mtl-status--pending"></span>调用中';
            html += `<tr>
                <td>${b.classSimpleName}</td>
                <td>${methodSig}</td>
                <td>${start}</td>
                <td>${end}</td>
                <td>${period}</td>
                <td>${status}</td>
                <td><a href="javascript:void(0);" onclick="window.MTLTrace && window.MTLTrace.openModal('${b.traceid}')">查看</a></td>
            </tr>`;
        });
        html += '</tbody></table></div>';
        el.innerHTML = html;
    }

    // 给 traces tab 复用
    function renderTraceRows(data) {
        if (!data || data.length === 0) return '<div class="empty-state"><div class="empty-state__icon">' + (window.mtlIcon ? window.mtlIcon('search-x') : '') + '</div><div class="empty-state__title">未匹配到调用记录</div><div class="empty-state__hint">试试调整过滤条件或扩大范围</div></div>';
        let html = `<div class="table-wrapper"><table><thead><tr>
                <th>类名</th><th>方法名</th><th>开始时间</th><th>结束时间</th><th>耗时(ms)</th><th>状态</th><th>链路</th>
            </tr></thead><tbody>`;
        data.forEach(item => {
            const b = item.before;
            const methodSig = b.methodSignatureLongString.split(' ').pop().replace(b.className + '.', '');
            const start = new Date(b.timeMillis).toLocaleString('zh-CN', timeOptions);
            const end = item.after ? new Date(item.after.timeMillis).toLocaleString('zh-CN', timeOptions) : 'N/A';
            const period = item.after ? (item.after.timeMillis - b.timeMillis) : 'N/A';
            const status = item.after
                ? (item.after.logActionEnum === 'AFTER_RETURN'
                    ? '<span class="mtl-status mtl-status--success"></span>成功'
                    : '<span class="mtl-status mtl-status--error"></span>失败')
                : '<span class="mtl-status mtl-status--pending"></span>调用中';
            html += `<tr>
                <td>${b.classSimpleName}</td>
                <td>${methodSig}</td>
                <td>${start}</td>
                <td>${end}</td>
                <td>${period}</td>
                <td>${status}</td>
                <td><a href="javascript:void(0);" onclick="window.MTLTrace && window.MTLTrace.openModal('${b.traceid}')">查看</a></td>
            </tr>`;
        });
        html += '</tbody></table></div>';
        return html;
    }

    function updateCallServices(data) {
        const container = document.getElementById('call-service-container');
        container.innerHTML = '';
        data.forEach(item => {
            const btn = document.createElement('button');
            if (item.enable) {
                btn.innerHTML = (window.mtlIconHTML ? window.mtlIconHTML('check-circle', '关闭 ' + item.desc) : '关闭 ' + item.desc);
                btn.className = 'btn btn-success';
                btn.addEventListener('click', () => updateCallMethods(item.name, false));
            } else {
                btn.innerHTML = (window.mtlIconHTML ? window.mtlIconHTML('stop-circle', '开启 ' + item.desc) : '开启 ' + item.desc);
                btn.className = 'btn btn-toggle';
                btn.addEventListener('click', () => updateCallMethods(item.name, true));
            }
            container.appendChild(btn);
        });
    }
    function updateCallMethods(name, enable) {
        mtlFetch('/methodTraceLog/view/callService?name=' + name + '&enable=' + enable)
            .then(r => r.json())
            .then(updateCallServices)
            .catch(e => MTL.toast(e.message));
    }

    // ===== Modal: trace tree =====
    function createTree(data, container, isRoot) {
        const nodeContainer = document.createElement('div');
        nodeContainer.className = 'tree-node';
        if (isRoot) nodeContainer.classList.add('tree-root');
        // 状态 class(供 CSS 着色 + pulse 动画用)
        if (!data.after) nodeContainer.classList.add('is-pending');
        else if (data.after.logActionEnum === 'AFTER_RETURN') nodeContainer.classList.add('is-success');
        else if (data.after.logActionEnum === 'AFTER_THROW') nodeContainer.classList.add('is-error');
        nodeContainer.appendChild(createNodeElement(data));
        if (data.children && data.children.length > 0) {
            data.children.forEach(child => createTree(child, nodeContainer, false));
        }
        container.appendChild(nodeContainer);
    }
    function createNodeElement(nodeData) {
        const nodeElement = document.createElement('div');
        nodeElement.className = 'tree-node__inner';
        const content = document.createElement('div');
        content.className = 'node-content';

        const className = nodeData.before.className;
        const methodSignatureLongString = nodeData.before.methodSignatureLongString;
        const methodSignature = methodSignatureLongString.split(' ').pop().replace(className + '.', '');

        const statusIcon = !nodeData.after
            ? 'alert-circle'
            : (nodeData.after.logActionEnum === 'AFTER_RETURN' ? 'check-circle' : 'x-circle');
        content.innerHTML = (window.mtlIcon ? window.mtlIcon(statusIcon) : '')
            + MTL.escapeHtml(nodeData.before.classSimpleName + '#' + methodSignature);

        const infoPanel = document.createElement('div');
        infoPanel.className = 'node-info';

        const addInfoItem = (label, value) => {
            const item = document.createElement('div');
            item.className = 'node-info-item';
            const labelElem = document.createElement('span');
            labelElem.className = 'node-info-label';
            labelElem.textContent = label + ':';
            const valueElem = document.createElement('span');
            valueElem.className = 'node-info-value';
            valueElem.textContent = value;
            item.appendChild(labelElem);
            item.appendChild(valueElem);
            infoPanel.appendChild(item);
        };

        let result = '';
        if (nodeData.after) {
            result = JSON.stringify(nodeData.after.context);
            if (result.length > 150) result = result.substring(0, 150) + '...';
        }
        addInfoItem('追踪ID', nodeData.before.traceid);
        addInfoItem('跨度ID', nodeData.before.spanid);
        addInfoItem('父跨度ID', nodeData.before.pspanid || '无');
        addInfoItem('类', className);
        addInfoItem('方法', methodSignatureLongString.replace(className + '.', ''));
        addInfoItem('参数', JSON.stringify(nodeData.before.context));
        addInfoItem('结果', result);
        addInfoItem('调用开始时间', new Date(nodeData.before.timeMillis).toLocaleString('zh-CN', timeOptions));
        addInfoItem('调用结束时间', nodeData.after ? new Date(nodeData.after.timeMillis).toLocaleString('zh-CN', timeOptions) : 'N/A');
        addInfoItem('耗时(ms)', nodeData.after ? nodeData.after.timeMillis - nodeData.before.timeMillis : 'N/A');

        nodeElement.appendChild(content);
        nodeElement.appendChild(infoPanel);
        return nodeElement;
    }
    function openTraceModal(id) {
        mtlFetch(`/methodTraceLog/view/traceid?id=${id}`)
            .then(r => r.json())
            .then(data => {
                const container = document.getElementById('trace-tree');
                container.innerHTML = '';
                createTree(data, container, true);
                MTL.openModal();
            })
            .catch(e => MTL.toast(e.message));
    }
    // 暴露给其它 tab 调用
    window.MTLTrace = { openModal: openTraceModal, renderTraceRows };

    // ===== Tab 生命周期 =====
    function onShow() {
        const refreshBtn = document.getElementById('refreshBtn');
        if (refreshBtn && !refreshBtn._mtlBound) {
            refreshBtn.addEventListener('click', loadData);
            refreshBtn._mtlBound = true;
        }

        const autoRefresh = document.getElementById('autoRefresh');
        const refreshInterval = document.getElementById('refreshInterval');
        if (autoRefresh && !autoRefresh._mtlBound) {
            autoRefresh.addEventListener('change', function () {
                if (this.checked) {
                    const interval = parseInt(refreshInterval.value);
                    refreshIntervalId = setInterval(loadData, interval);
                } else {
                    clearInterval(refreshIntervalId);
                }
            });
            autoRefresh._mtlBound = true;
        }
        if (refreshInterval && !refreshInterval._mtlBound) {
            refreshInterval.addEventListener('change', function () {
                if (autoRefresh.checked) {
                    clearInterval(refreshIntervalId);
                    refreshIntervalId = setInterval(loadData, parseInt(this.value));
                }
            });
            refreshInterval._mtlBound = true;
        }
        loadData();
    }
    function onHide() {
        if (refreshIntervalId) { clearInterval(refreshIntervalId); refreshIntervalId = null; }
    }

    document.addEventListener('DOMContentLoaded', () => {
        MTL.registerTab('overview', { onShow, onHide });
    });
})();
