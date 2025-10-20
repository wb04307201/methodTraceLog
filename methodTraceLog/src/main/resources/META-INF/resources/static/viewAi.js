let refreshIntervalId;
let modal;
let modalAna;
let anaCodeData;
let complexityChart;
let complexityDistributionChart;
let traceData;
let anaTraceData;

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
    document.getElementById("modal-close-btn").addEventListener("click", () => {
        modal.style.display = "none";
        document.getElementById('trace-waiting').style.display = 'block';
        document.getElementById('trace-loading').style.display = 'none';
        document.getElementById('trace-tabs-container').style.display = 'none';
    });

    modalAna = document.getElementById("modal-ana");
    // 关闭弹出框（点击 × 按钮）
    document.getElementById("modal-ana-close-btn").addEventListener("click", () => {
        modalAna.style.display = "none";
        document.getElementById('analysis-waiting').style.display = 'block';
        document.getElementById('analysis-loading').style.display = 'none';
        document.getElementById('analysis-tabs-container').style.display = 'none';
    });

    document.getElementById('ana-code-btn').addEventListener('click', anaCode);
    document.getElementById('trace-btn').addEventListener('click', anaTrace);
    document.getElementById('export-ana-json-btn').addEventListener('click', exportJsonData);
    document.getElementById('export-trace-json-btn').addEventListener('click', exportTraceJsonData);

});

// Tabs切换功能
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('tab-item')) {
        const tabName = e.target.getAttribute('data-tab');

        // 更新tab头部样式
        document.querySelectorAll('.tab-item').forEach(item => {
            item.classList.remove('active');
        });
        e.target.classList.add('active');

        // 显示对应的内容
        document.querySelectorAll('.tab-content').forEach(content => {
            content.classList.remove('active');
        });
        document.getElementById(`${tabName}-tab`).classList.add('active');
    }
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
                            <th>时间复杂度</th>
                    </tr>
                </thead>
            <tbody>
            `;

    data.forEach(item => {
        tableHTML += `
                    <tr>
                        <td>${item.className.split('.').pop()}</td>
                        <td>${item.methodSignature.split(' ').pop().replace(item.className + '.', '')}</td>
                        <td>${item.totalCalls.toLocaleString()}</td>
                        <td>${item.successCalls.toLocaleString()}</td>
                        <td>${item.successRate.toFixed(2)}%</td>
                        <td>${item.averageSuccessTime ? item.averageSuccessTime.toFixed(2) : 'N/A'}</td>
                        <td><a href="javascript:void(0);" onclick="openModala('${item.className}','${item.methodSignature}')">分析</a></td>
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
            traceData = data;

            const container = document.getElementById('trace-tree');
            container.innerHTML = '';
            createTree(data, container);

            modal.style.display = 'block';
        })
        .catch(error => {
            showToast('❌ 发生异常: ' + error.message);
        })
}

function openModala(className, methodSignature) {
    modalAna.style.display = "block";

    let methodName = methodSignature.split(' ').pop().replace(className + '.', '').split('(')[0];

    fetch(`/methodTraceLog/view/methodSourceCode?className=${className}&methodName=${methodName}`)
        .then(response => response.text())
        .then(text => {
            document.getElementById('source-code').textContent = text;

            modalAna.style.display = "block";
        })
        .catch(error => {
            showToast('❌ 发生异常: ' + error.message);
        })
}

function anaCode(){
    anaData = null;
    // 显示加载状态，隐藏分析内容
    document.getElementById('analysis-waiting').style.display = 'none';
    document.getElementById('analysis-loading').style.display = 'block';
    document.getElementById('analysis-tabs-container').style.display = 'none';

    fetch('/methodTraceLog/view/timeComplexity',{
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                "sourceCode": document.getElementById("source-code").value
            }),
        })
        .then(response => response.json())
        .then(data => {
            anaCodeData = data;

            document.getElementById("complexity-value").textContent = data.overallComplexity
            updateConfidenceProgress(data.confidence,'confidence-fill','confidence-text')
            updatePerformanceRating(data.overallComplexity)
            updateComplexityChart(data.visualData.chartData)
            updateComplexityDistribution(data.visualData.complexityBreakdown)
            document.getElementById("explanation-value").textContent = data.explanation
            updateLineAnalysis(data.lineAnalysis);
            updateOptimizationSuggestions(data.suggestions)

            // 隐藏加载状态，显示分析结果
            document.getElementById('analysis-waiting').style.display = 'none';
            document.getElementById('analysis-loading').style.display = 'none';
            document.getElementById('analysis-tabs-container').style.display = 'block';
        })
        .catch(error => {
            showToast('❌ 发生异常: ' + error.message);
            document.getElementById('analysis-waiting').style.display = 'block';
            document.getElementById('analysis-loading').style.display = 'none';
            document.getElementById('analysis-tabs-container').style.display = 'none';
        })
}

function updateConfidenceProgress(percentage,fill,text) {
    const fillElement = document.getElementById(fill);
    const textElement = document.getElementById(text);

    // 限制百分比在0-100之间
    const safePercentage = Math.min(100, Math.max(0, percentage));

    fillElement.style.width = safePercentage + '%';
    textElement.textContent = safePercentage + '%';

    // 根据百分比设置颜色
    if (safePercentage >= 80) {
        fillElement.style.backgroundColor = '#28a745'; // 绿色
    } else if (safePercentage >= 60) {
        fillElement.style.backgroundColor = '#ffc107'; // 黄色
    } else {
        fillElement.style.backgroundColor = '#dc3545'; // 红色
    }
}

// 根据评级设置相应的CSS类
function updatePerformanceRating(ratingText) {
    const ratingElement = document.getElementById('performance-rating');

    // 清除之前的类
    ratingElement.className = 'metric-value';

    // 根据评级添加相应类
    switch(ratingText) {
        case 'O(1)':
            ratingElement.textContent = '优秀';
            ratingElement.classList.add('excellent');
            break;
        case 'O(log n)':
            ratingElement.textContent = '良好';
            ratingElement.classList.add('good');
            break;
        case 'O(n)':
            ratingElement.textContent = '一般';
            ratingElement.classList.add('average');
            break;
        case 'O(n log n)':
            ratingElement.textContent = '容忍';
            ratingElement.classList.add('acceptable');
            break;
        case 'O(n²)':
            ratingElement.textContent = '较差';
            ratingElement.classList.add('poor');
            break;
        case 'O(2^n)':
            ratingElement.textContent = '极差';
            ratingElement.classList.add('terrible');
            break;
        default:
            ratingElement.textContent = '未知';
            break;
    }
}

function updateComplexityChart(chartData) {
    // 正确销毁已存在的图表实例
    if (complexityChart)
        complexityChart.destroy();

    // 按复杂度分组数据
    const groupedData = chartData.reduce((acc, item) => {
        if (!acc[item.complexity]) {
            acc[item.complexity] = [];
        }
        acc[item.complexity].push(item);
        return acc;
    }, {});

    // 构造图表数据集
    const datasets = Object.keys(groupedData).map((complexity, index) => {
        const color = index === 0 ? 'rgb(255, 99, 132)' : 'rgb(54, 162, 235)';

        return {
            label: complexity,
            data: groupedData[complexity].map(item => ({
                x: item.inputSize,
                y: item.operations
            })),
            borderColor: color,
            backgroundColor: color.replace(')', ', 0.2)').replace('rgb', 'rgba'),
            tension: 0.1,
            fill: false
        };
    });

    // 创建图表
    const ctx = document.getElementById('complexityChart').getContext('2d');
    complexityChart = new Chart(ctx, {
        type: 'line',
        data: {
            datasets: datasets
        },
        options: {
            responsive: true,
            maintainAspectRatio: false, // 必须设为false以允许自定义高度
            height: '200px',
            plugins: {
                legend: {
                    display: true,
                    position: 'top'
                }
            },
            scales: {
                x: {
                    type: 'linear',
                    position: 'bottom',
                    title: {
                        display: true,
                        text: '输入规模 (inputSize)'
                    }
                },
                y: {
                    title: {
                        display: true,
                        text: '操作次数 (operations)'
                    }
                }
            }
        }
    });
}

function updateComplexityDistribution(complexityBreakdown) {
    if (complexityDistributionChart)
        complexityDistributionChart.destroy();

    const ctx = document.getElementById('complexityDistributionChart').getContext('2d');
    // 创建饼图
    complexityDistributionChart = new Chart(ctx, {
        type: 'pie',
        data: {
            labels: complexityBreakdown.map(item => `${item.section} (${item.complexity})`),
            datasets: [{
                data: complexityBreakdown.map(item => item.percentage),
                backgroundColor: complexityBreakdown.map(item => item.color),
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false, // 必须设为false以允许自定义高度
            height: '200px',
            plugins: {
                legend: {
                    position: 'bottom',
                },
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            const label = context.label || '';
                            const value = context.raw || 0;
                            return `${label}: ${value}%`;
                        }
                    }
                }
            }
        }
    });
}

function updateLineAnalysis(data) {
    const container = document.getElementById('line-analysis-container');
    container.innerHTML = '';

    data.forEach(item => {
        const card = document.createElement('div');
        card.className = 'line-analysis-card';

        const complexityClass = getComplexityClass(item.complexity);

        card.innerHTML = `
            <div class="line-analysis-header">
                <span class="line-number">第 ${item.lineNumber} 行</span>
                <span class="complexity-badge ${complexityClass}">${item.complexity}</span>
            </div>
            <div class="line-code">${escapeHtml(item.code)}</div>
            <div class="line-explanation">${escapeHtml(item.explanation)}</div>
        `;

        container.appendChild(card);
    });
}

function getComplexityClass(complexity) {
    const classMap = {
        'O(1)': 'complexity-O1',
        'O(log n)': 'complexity-Ologn',
        'O(n)': 'complexity-On',
        'O(n log n)': 'complexity-Onlogn',
        'O(n²)': 'complexity-On2',
        'O(2^n)': 'complexity-O2n'
    };
    return classMap[complexity] || '';
}

function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}

function updateOptimizationSuggestions(suggestions) {
    const container = document.getElementById('suggestions-container');
    container.innerHTML = '';

    suggestions.forEach(item => {
        const card = document.createElement('div');
        card.className = 'suggestion-card';

        const impactClass = getImpactClass(item.impact);
        const impactValue = getImpactTransValue(item.impact);
        // const typeClass = getTypeClass(item.type);
        const typeValue = getTypeTransValue(item.type)

        card.innerHTML = `
            <div class="suggestion-header">
                <span class="suggestion-title">${escapeHtml(item.title)}</span>
                <span>
                    <span class="suggestion-type">${typeValue}</span>
                    <span class="suggestion-impact ${impactClass}">${impactValue}</span>
                </span>
            </div>
            <div class="suggestion-description">${escapeHtml(item.description)}</div>
            <div class="suggestion-code"><pre>${escapeHtml(item.codeExample)}</pre></div>
        `;

        container.appendChild(card);
    });
}

function getImpactClass(impact) {
    const classMap = {
        'high': 'impact-high',
        'medium': 'impact-medium',
        'low': 'impact-low'
    };
    return classMap[impact] || '';
}

function getImpactTransValue(impact) {
    const classMap = {
        'high': '高',
        'medium': '中',
        'low': '低'
    };
    return classMap[impact] || '';
}

function getTypeTransValue(type) {
    const classMap = {
        "space-time-tradeoff": '空间-时间权衡',
        'algorithm-refactor': '算法重构',
        'data-structure': '数据结构',
        'loop-optimization': '循环优化'
    };
    return classMap[type] || '';
}

function anaTrace(){
    document.getElementById('trace-waiting').style.display = 'none';
    document.getElementById('trace-loading').style.display = 'block';
    document.getElementById('trace-tabs-container').style.display = 'none';

    fetch('/methodTraceLog/view/callChain',{
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(traceData),
    })
        .then(response => response.json())
        .then(data => {
            anaData = data;

            document.getElementById("overallAssessment-value").textContent = data.overallAssessment
            updateConfidenceProgress(data.confidence,'trace-confidence-fill','trace-confidence-text')
            renderPerformanceBottlenecks(data.bottlenecks);
            renderOptimizationSuggestions(data.suggestions)

            // 隐藏加载状态，显示分析结果
            document.getElementById('trace-waiting').style.display = 'none';
            document.getElementById('trace-loading').style.display = 'none';
            document.getElementById('trace-tabs-container').style.display = 'block';
        })
        .catch(error => {
            showToast('❌ 发生异常: ' + error.message);
            document.getElementById('trace-waiting').style.display = 'block';
            document.getElementById('trace-loading').style.display = 'none';
            document.getElementById('trace-tabs-container').style.display = 'none';
        })
}

function renderPerformanceBottlenecks(data) {
    const container = document.getElementById('performance-bottlenecks');
    if (!container) return;

    container.innerHTML = '';

    data.forEach(item => {
        const bottleneckDiv = document.createElement('div');
        bottleneckDiv.className = 'trace-item';

        bottleneckDiv.innerHTML = `      <div class="bottleneck-header">
        <span class="bottleneck-class">${item.className}</span>
        <span class="bottleneck-method">${item.methodName}</span>
      </div>
      <div class="bottleneck-issue">
        <strong>问题:</strong> ${item.issue}      </div>
      <div class="bottleneck-recommendation">
        <strong>建议:</strong> ${item.recommendation}      </div>
    `;

        container.appendChild(bottleneckDiv);
    });
}

function renderOptimizationSuggestions(data) {
    const container = document.getElementById('optimization-suggestions');
    if (!container) return;

    container.innerHTML = '';

    data.forEach(item => {
        const bottleneckDiv = document.createElement('div');
        bottleneckDiv.className = `trace-item ${getPriorityClass(item.priority)}`;

        bottleneckDiv.innerHTML = `<div class="suggestion-category">${item.category}</div>
    <div class="suggestion-description">${item.description}</div>
    <div class="suggestion-priority"><strong>优先级:</strong>${getPriorityTransValue(item.priority)}</div>`;

        container.appendChild(bottleneckDiv);
    });
}

function getPriorityClass(priority) {
    const classMap = {
        'high': 'priority-high',
        'medium': 'priority-medium',
        'low': 'priority-low'
    };
    return classMap[priority] || '';
}

function getPriorityTransValue(priority) {
    const classMap = {
        'high': '高',
        'medium': '中',
        'low': '低'
    };
    return classMap[priority] || '';
}

function exportJsonData(){
    if(anaData!=null){
        var jsonString = JSON.stringify(anaCodeData);

        // 创建Blob对象
        const blob = new Blob([jsonString], { type: 'application/json' });

        // 创建下载链接
        const downloadLink = document.createElement('a');
        downloadLink.href = URL.createObjectURL(blob);
        downloadLink.download = 'anaCodeData.json'; // 设置文件名

        // 触发下载
        document.body.appendChild(downloadLink);
        downloadLink.click();
        document.body.removeChild(downloadLink);
    }
}

function exportTraceJsonData(){
    if(anaData!=null){
        var jsonString = JSON.stringify(anaTraceData);

        // 创建Blob对象
        const blob = new Blob([jsonString], { type: 'application/json' });

        // 创建下载链接
        const downloadLink = document.createElement('a');
        downloadLink.href = URL.createObjectURL(blob);
        downloadLink.download = 'anaTraceData.json'; // 设置文件名

        // 触发下载
        document.body.appendChild(downloadLink);
        downloadLink.click();
        document.body.removeChild(downloadLink);
    }
}