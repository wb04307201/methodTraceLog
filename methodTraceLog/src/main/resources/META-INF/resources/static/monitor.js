let refreshIntervalId;

document.addEventListener('DOMContentLoaded', function () {
    // 首次加载数据
    loadData();

    // 刷新按钮事件
    document.getElementById('refreshBtn').addEventListener('click', loadData);

    // 自动刷新切换
    document.getElementById('autoRefresh').addEventListener('change', function () {
        if (this.checked) {
            const interval = parseInt(document.getElementById('refreshInterval').value);
            refreshIntervalId = setInterval(loadData, interval);
        } else {
            clearInterval(refreshIntervalId);
        }
    });

    // 刷新间隔改变事件
    document.getElementById('refreshInterval').addEventListener('change', function () {
        if (document.getElementById('autoRefresh').checked) {
            clearInterval(refreshIntervalId);
            const interval = parseInt(this.value);
            refreshIntervalId = setInterval(loadData, interval);
        }
    });

    // 关闭弹出框（点击 × 按钮）
    document.querySelector(".close-btn").addEventListener("click", () => {
        modal.style.display = "none";
    });
});

function loadData() {
    fetch('/actuator/methodtrace')
        .then(response => response.json())
        .then(data => {
            updateSummary(data);
            updateTable(data);
        })
        .catch(error => {
            console.error('加载数据失败:', error);
            alert('加载数据失败，请稍后重试');
        });

    fetch('/log/monitor/view/list')
        .then(response => response.json())
        .then(data => {
            updateMethodTable(data);
        })
        .catch(error => {
            console.error('加载数据失败:', error);
            alert('加载数据失败，请稍后重试');
        });
}

function updateSummary(data) {
    let totalCount = 0;
    let successCount = 0;
    let failureCount = 0;

    data.forEach(item => {
        totalCount += item.totalCalls;
        successCount += item.successCalls;
        failureCount += item.failedCalls;
    });

    const avgSuccessRate = totalCount > 0 ? ((successCount / totalCount) * 100).toFixed(2) : 0;

    document.getElementById('totalCount').textContent = totalCount.toLocaleString();
    document.getElementById('successCount').textContent = successCount.toLocaleString();
    document.getElementById('failureCount').textContent = failureCount.toLocaleString();
    document.getElementById('avgSuccessRate').textContent = avgSuccessRate + '%';
}

function updateTable(data) {
    const methodtrace = document.getElementById('methodtrace');

    if (data.length === 0) {
        methodtrace.innerHTML = `
                    <div class="empty-state">
                        <p>暂无数据</p>
                    </div>
                `;
        return;
    }

    let tableHTML = `
                <table>
                    <thead>
                        <tr>
                            <th>类名</th>
                            <th>方法签名</th>
                            <th>总调用次数</th>
                            <th>成功调用</th>
                            <th>失败调用</th>
                            <th>成功率</th>
                            <th>失败率</th>
                            <th>平均成功时间(ms)</th>
                            <th>平均失败时间(ms)</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

    data.forEach(item => {
        tableHTML += `
                    <tr>
                        <td>${item.className}</td>
                        <td>${item.methodSignature}</td>
                        <td>${item.totalCalls.toLocaleString()}</td>
                        <td class="success-rate">${item.successCalls.toLocaleString()}</td>
                        <td class="failure-rate">${item.failedCalls.toLocaleString()}</td>
                        <td class="success-rate">${item.successRate.toFixed(2)}%</td>
                        <td class="failure-rate">${item.failureRate.toFixed(2)}%</td>
                        <td>${item.averageSuccessTime ? item.averageSuccessTime.toFixed(2) : 'N/A'}</td>
                        <td>${item.averageFailureTime ? item.averageFailureTime.toFixed(2) : 'N/A'}</td>
                    </tr>
                `;
    });

    tableHTML += `
                    </tbody>
                </table>
            `;

    methodtrace.innerHTML = tableHTML;
}

function updateMethodTable(data) {
    const method = document.getElementById('method');

    if (data.length === 0) {
        method.innerHTML = `
                    <div class="empty-state">
                        <p>暂无数据</p>
                    </div>
                `;
        return;
    }

    let tableHTML = `
                <table>
                    <thead>
                        <tr>
                            <th>类名</th>
                            <th>方法签名</th>
                            <th>调用开始时间</th>
                            <th>调用结束时间</th>
                            <th>耗时(ms)</th>
                            <th>调用状态</th>
                            <th>调用链</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

    data.forEach(item => {
        let traceid = item.before.traceid;
        let className = item.before.className;
        let methodSignature = item.before.methodSignature;
        let start = new Date(item.before.timeMillis).toLocaleString()
        let end = item.after != null ? new Date(item.after.timeMillis).toLocaleString() : "N/A";
        let period = item.after != null ? (item.after.timeMillis - item.before.timeMillis) : "N/A";
        let status = item.after != null ? (item.after.logActionEnum == "AFTER_RETURN" ? "🟢成功" : "🔴失败") : "🟡调用中";

        tableHTML += `
                    <tr>
                        <td>${className}</td>
                        <td>${methodSignature}</td>
                        <td>${start}</td>
                        <td>${end}</td>
                        <td>${period}</td>
                        <td>${status}</td>
                        <td><a href="javascript:void(0);" onclick="openModal('${traceid}')">查看</a>
</td>
                    </tr>
                `;
    });

    tableHTML += `
                    </tbody>
                </table>
            `;

    method.innerHTML = tableHTML;
}

function openModal(id) {
    fetch('/log/monitor/view/traceid?id=' + id)
        .then(response => response.json())
        .then(data => {
            const container = document.getElementById('tree-container');
            container.innerHTML = '';
            createTree(data, container);

            const modal = document.getElementById("modal");
            modal.style.display = 'block';
        })
        .catch(error => {
            console.error('加载数据失败:', error);
        })
}

// 创建树形结构
function createTree(data, container) {
    // 创建节点容器
    const nodeContainer = document.createElement('div');
    nodeContainer.className = 'tree-node';

    // 处理BEFORE节点
    const beforeNode = createNodeElement(data);
    nodeContainer.appendChild(beforeNode);

    // 处理子节点
    if (data.children && data.children.length > 0) {
        data.children.forEach(child => {
            createTree(child, nodeContainer);
        });
    }

    container.appendChild(nodeContainer);
}

// 创建单个节点元素
function createNodeElement(nodeData) {
    const nodeElement = document.createElement('div');

    // 创建节点内容
    const content = document.createElement('div');
    content.className = 'node-content';

    // 显示简化的节点信息
    const className = nodeData.before.className.split('.').pop();
    const methodName = nodeData.before.methodSignature.split(' ').pop().split('(')[0];
    if(!nodeData.after){
        content.textContent = `🟡`;
    }else if(nodeData.after.logActionEnum === 'AFTER_RETURN'){
        content.textContent = `🟢`;
    }else if(nodeData.after.logActionEnum === 'AFTER_THROWING'){
        content.textContent = `🔴`;
    }
    content.textContent += `${className}#${methodName}()`;

    // 创建节点信息面板
    const infoPanel = document.createElement('div');
    infoPanel.className = 'node-info';

    // 添加详细信息
    const addInfoItem = (label, value) => {
        const item = document.createElement('div');
        item.className = 'node-info-item';

        const labelElem = document.createElement('span');
        labelElem.className = 'node-info-label';
        labelElem.textContent = label + ':';

        const valueElem = document.createElement('span');
        valueElem.textContent = value;

        item.appendChild(labelElem);
        item.appendChild(valueElem);
        infoPanel.appendChild(item);
    };

    addInfoItem('追踪ID', nodeData.before.traceid);
    addInfoItem('跨度ID', nodeData.before.spanid);
    addInfoItem('父跨度ID', nodeData.before.pspanid || '无');
    addInfoItem('参数', JSON.stringify(nodeData.before.context));
    addInfoItem('结果',  nodeData.after ? JSON.stringify(nodeData.after.context) : "");
    addInfoItem('调用开始时间', new Date(nodeData.before.timeMillis).toLocaleString());
    addInfoItem('调用结束时间', nodeData.after ? new Date(nodeData.after.timeMillis).toLocaleString() : "N/A");
    addInfoItem('耗时(ms)', nodeData.after ? nodeData.after.timeMillis - nodeData.before.timeMillis : "N/A");

    nodeElement.appendChild(content);
    nodeElement.appendChild(infoPanel);

    return nodeElement;
}