let refreshIntervalId;
let modal;

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

    modal = document.getElementById("modal");
    // 关闭弹出框（点击 × 按钮）
    document.querySelector(".close-btn").addEventListener("click", () => {
        modal.style.display = "none";
    });
});


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
            <div class="table-wrapper">
                <table>
                    <thead>
                    <tr>
                            <th>类名</th>
                            <th>方法名</th>
                            <th>调用</th>
                            <th>成功</th>
                            <th>成功率</th>
                            <th>耗时(ms)</th>
                    </tr>
                </thead>
            <tbody>
            `;

    data.forEach(item => {
        tableHTML += `
                    <tr>
                        <td>${item.className}</td>
                        <td>${item.methodSignature.split(' ').pop().replace(item.className + '.', '')}</td>
                        <td>${item.totalCalls.toLocaleString()}</td>
                        <td>${item.successCalls.toLocaleString()}</td>
                        <td>${item.successRate.toFixed(2)}%</td>
                        <td>${item.averageSuccessTime ? item.averageSuccessTime.toFixed(2) : 'N/A'}</td>
                    </tr>
                `;
    });

    tableHTML += `
            </tbody>
        </table>
    </div>
            `;

    methodtrace.innerHTML = tableHTML;
}

function openModal(id) {
    fetch(`/methodTraceLog/view/traceid?id=${id}`)
        .then(response => response.json())
        .then(data => {
            const container = document.getElementById('trace-tree');
            container.innerHTML = '';
            createTree(data, container);

            modal.style.display = 'block';
        })
        .catch(error => {
            showToast('❌ 发生异常: ' + error.message);
        })
}