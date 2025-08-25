let currentFile = null;
let stompClient = null;
let isConnected = false;
let isMonitoring = false;
let realtimeLogCount = 0;
const MAX_REALTIME_LOGS = 1000; // 最大实时日志行数

let currentQuery = {
    fileName: '', page: 1, pageSize: 100, keyword: '', level: '', startTime: null, endTime: null, reverse: true
};
let totalPages = 1;

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function () {
    loadFileList();
    initWebSocket();
});

// 加载文件列表
async function loadFileList() {
    try {
        const response = await fetch('/log/file/files');
        const files = await response.json();

        const fileListEl = document.getElementById('fileList');

        if (files.length === 0) {
            fileListEl.innerHTML = '<div class="error">未找到日志文件</div>';
            return;
        }

        fileListEl.innerHTML = files.map(file => `
                    <div class="file-item" onclick="selectFile('${file.name}')">
                        <div class="file-name">${file.name}</div>
                        <div class="file-info">
                            <span>${formatFileSize(file.size)}</span>
                            <span>${formatDate(file.lastModified)}</span>
                        </div>
                    </div>
                `).join('');

    } catch (error) {
        console.error('加载文件列表失败:', error);
        document.getElementById('fileList').innerHTML = '<div class="error">加载文件列表失败</div>';
    }
}

// 选择文件
function selectFile(fileName) {
    // 如果正在监控中，关闭之前的监控监控
    if (isMonitoring) {
        stopRealtimeMonitor();
    }

    // 移除之前的选中状态
    document.querySelectorAll('.file-item').forEach(item => {
        item.classList.remove('selected');
    });

    // 添加选中状态
    event.target.closest('.file-item').classList.add('selected');

    currentFile = fileName;
    currentQuery.fileName = fileName;

    // 显示搜索面板
    document.getElementById('searchPanel').classList.remove('hidden');

    // 自动搜索第一页
    searchLogs();
}

// 搜索日志
async function searchLogs(page = 1) {
    if (!currentFile) {
        alert('请先选择日志文件');
        return;
    }

    // 更新查询参数
    currentQuery.page = page;
    currentQuery.pageSize = parseInt(document.getElementById('pageSize').value);
    currentQuery.keyword = document.getElementById('keyword').value;
    currentQuery.level = document.getElementById('level').value;
    currentQuery.reverse = document.getElementById('reverse').value === 'true';

    const startTime = document.getElementById('startTime').value;
    const endTime = document.getElementById('endTime').value;
    currentQuery.startTime = startTime ? startTime + ':00' : null;
    currentQuery.endTime = endTime ? endTime + ':00' : null;

    try {
        document.getElementById('logContent').innerHTML = '<div class="loading">正在搜索日志...</div>';
        document.getElementById('resultPanel').classList.remove('hidden');

        const response = await fetch('/log/file/query', {
            method: 'POST', headers: {
                'Content-Type': 'application/json'
            }, body: JSON.stringify(currentQuery)
        });

        if (!response.ok) {
            throw new Error('搜索失败');
        }

        const result = await response.json();
        displayResults(result);

    } catch (error) {
        console.error('搜索日志失败:', error);
        document.getElementById('logContent').innerHTML = '<div class="error">搜索日志失败</div>';
    }
}

function resetLogs() {
    document.getElementById('pageSize').value = 100;
    document.getElementById('keyword').value = "";
    currentQuery.level = document.getElementById('level').value = "";
    currentQuery.reverse = document.getElementById('reverse').value === 'true';
    document.getElementById('startTime').value = "";
    document.getElementById('endTime').value = "";

    searchLogs();
}


// 显示搜索结果
function displayResults(result) {
    // 更新文件信息
    document.getElementById('fileName').textContent = currentFile;
    document.getElementById('fileInfo').textContent = ` (${formatFileSize(result.fileSize)}, 修改时间: ${formatDateTime(result.lastModified)})`;

    // 更新统计信息
    document.getElementById('resultStats').textContent = `共找到 ${result.totalLines} 行，第 ${result.currentPage}/${result.totalPages} 页`;

    // 更新分页信息
    totalPages = result.totalPages;
    document.getElementById('currentPage').value = result.currentPage;
    document.getElementById('totalPages').textContent = result.totalPages;
    document.getElementById('paginationInfo').textContent = `显示第 ${(result.currentPage - 1) * currentQuery.pageSize + 1} - ${Math.min(result.currentPage * currentQuery.pageSize, result.totalLines)} 行`;

    // 更新按钮状态
    document.getElementById('prevBtn').disabled = result.currentPage <= 1;
    document.getElementById('nextBtn').disabled = result.currentPage >= result.totalPages;

    // 显示日志内容
    const logContentEl = document.getElementById('logContent');
    if (result.lines.length === 0) {
        logContentEl.innerHTML = '<div class="error">未找到匹配的日志</div>';
        return;
    }

    logContentEl.innerHTML = result.lines.map(line => {
        const className = getLogLineClass(line);
        return `<div class="log-line ${className}">${escapeHtml(line)}</div>`;
    }).join('');
}

// 获取日志行样式类
function getLogLineClass(line) {
    if (line.includes('ERROR')) return 'error';
    if (line.includes('WARN')) return 'warn';
    if (line.includes('INFO')) return 'info';
    return '';
}

// HTML转义
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 分页控制
function previousPage() {
    if (currentQuery.page > 1) {
        searchLogs(currentQuery.page - 1);
    }
}

function nextPage() {
    if (currentQuery.page < totalPages) {
        searchLogs(currentQuery.page + 1);
    }
}

function goToPage() {
    const page = parseInt(document.getElementById('currentPage').value);
    if (page >= 1 && page <= totalPages) {
        searchLogs(page);
    }
}

// 下载日志
async function downloadLog() {
    if (!currentFile) {
        alert('请先选择日志文件');
        return;
    }

    // 更新查询参数
    currentQuery.keyword = document.getElementById('keyword').value;
    currentQuery.level = document.getElementById('level').value;
    const startTime = document.getElementById('startTime').value;
    const endTime = document.getElementById('endTime').value;
    currentQuery.startTime = startTime ? startTime + ':00' : null;
    currentQuery.endTime = endTime ? endTime + ':00' : null;

    try {
        const response = await fetch('/log/file/download', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(currentQuery)
        });

        if (!response.ok) {
            throw new Error(`下载失败`);
        }

        const blob = await response.blob();
        const blobUrl = window.URL.createObjectURL(blob);

        // 从响应头获取文件名（如果服务器提供了）
        const contentDisposition = response.headers.get('content-disposition');
        let suggestedFilename = currentQuery.fileName;
        if (contentDisposition) {
            const filenameMatch = contentDisposition.match(/filename="?(.+?)"?(;|$)/);
            if (filenameMatch && filenameMatch[1]) {
                suggestedFilename = filenameMatch[1];
            }
        }

        const a = document.createElement('a');
        a.href = blobUrl;
        a.download = suggestedFilename || 'downloaded-file';
        document.body.appendChild(a);
        a.click();

        // 清理
        window.URL.revokeObjectURL(blobUrl);
        document.body.removeChild(a);
    } catch (error) {
        console.error('下载日志失败:', error);
    }
}

// 工具函数
function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function formatDate(timestamp) {
    return new Date(timestamp).toLocaleDateString('zh-CN');
}

function formatDateTime(dateTimeStr) {
    return new Date(dateTimeStr).toLocaleString('zh-CN');
}

// ==================== WebSocket 实时日志功能 ====================

// 初始化WebSocket连接
function initWebSocket() {
    try {
        const socket = new SockJS('/ws');
        stompClient = new StompJs.Client({
            webSocketFactory: () => socket, debug: function (str) {
                console.log('STOMP: ' + str);
            }, reconnectDelay: 5000, heartbeatIncoming: 4000, heartbeatOutgoing: 4000
        });

        stompClient.onConnect = function (frame) {
            console.log('WebSocket连接成功: ' + frame);
            updateConnectionStatus(true);

            // 订阅日志监控主题
            stompClient.subscribe('/topic/log-monitor', function (message) {
                handleRealtimeMessage(JSON.parse(message.body));
            });

            // 发送心跳
            setInterval(() => {
                if (isConnected) {
                    stompClient.publish({
                        destination: '/app/heartbeat', body: JSON.stringify({message: 'ping'})
                    });
                }
            }, 30000);
        };

        stompClient.onStompError = function (frame) {
            console.error('WebSocket错误: ' + frame.headers['message']);
            console.error('详细信息: ' + frame.body);
            updateConnectionStatus(false);
        };

        stompClient.onWebSocketClose = function (event) {
            console.log('WebSocket连接关闭');
            updateConnectionStatus(false);
            updateMonitoringStatus(false);
        };

        stompClient.activate();

    } catch (error) {
        console.error('初始化WebSocket失败:', error);
        updateConnectionStatus(false);
    }
}

// 更新连接状态显示
function updateConnectionStatus(connected) {
    isConnected = connected;
    const statusEl = document.getElementById('connectionStatus');
    const textEl = document.getElementById('connectionText');

    if (connected) {
        statusEl.className = 'status-indicator connected';
        textEl.textContent = 'WebSocket已连接';
    } else {
        statusEl.className = 'status-indicator disconnected';
        textEl.textContent = 'WebSocket未连接';
    }
}

// 更新监控状态显示
function updateMonitoringStatus(monitoring) {
    isMonitoring = monitoring

    const statusEl = document.getElementById('connectionStatus');
    const textEl = document.getElementById('connectionText');
    const toggleBtn = document.getElementById('realtimeToggleBtn');
    const clearBtn = document.getElementById('clearRealtimeBtn');

    if (monitoring) {
        statusEl.className = 'status-indicator monitoring';
        textEl.textContent = '正在监控: ' + currentFile;
        toggleBtn.textContent = '🛑 停止监控';
        toggleBtn.classList.add('active');
        clearBtn.classList.remove('hidden')
        clearBtn.disabled = false
    } else {
        if (isConnected) {
            statusEl.className = 'status-indicator connected';
            textEl.textContent = 'WebSocket已连接';
        } else {
            statusEl.className = 'status-indicator disconnected';
            textEl.textContent = 'WebSocket未连接';
        }
        toggleBtn.textContent = '📡 实时监控';
        toggleBtn.classList.remove('active');
        clearBtn.disabled = true
    }
}

// 切换实时监控
function toggleRealtimeMonitor() {
    if (!isConnected) {
        alert('WebSocket未连接，无法开始监控');
        return;
    }

    if (!currentFile) {
        alert('请先选择日志文件');
        return;
    }

    if (isMonitoring) {
        stopRealtimeMonitor();
    } else {
        startRealtimeMonitor();
    }
}

// 开始实时监控
function startRealtimeMonitor() {
    if (!isConnected || !currentFile) {
        return;
    }

    stompClient.publish({
        destination: '/app/start-monitor', body: JSON.stringify({fileName: currentFile})
    });

    // 清空现有日志并显示监控状态
    clearRealtimeLogs();

    // 隐藏分页控件（实时模式下不需要分页）
    const pagination = document.querySelector('.pagination');
    if (pagination) {
        pagination.style.display = 'none';
    }
}

// 停止实时监控
function stopRealtimeMonitor() {
    if (!isConnected) {
        return;
    }

    stompClient.publish({
        destination: '/app/stop-monitor', body: JSON.stringify({fileName: currentFile})
    });

    // 显示分页控件
    const pagination = document.querySelector('.pagination');
    if (pagination) {
        pagination.style.display = 'flex';
    }

    // 重新加载当前页面的日志
    setTimeout(() => {
        searchLogs();
    }, 500);
}

// 处理实时消息
function handleRealtimeMessage(message) {
    console.log('收到实时消息:', message);

    switch (message.type) {
        case 'new_log_line':
            addRealtimeLog(message.content, message.level);
            break;
        case 'monitor_started':
            updateMonitoringStatus(true);
            break;
        case 'monitor_stopped':
            updateMonitoringStatus(false);
            break;
        case 'error':
            addRealtimeLog('错误: ' + message.message, 'error');
            break;
        case 'heartbeat':
            // 心跳响应，不需要显示
            break;
        default:
            console.log('未知消息类型:', message.type);
    }
}

// 添加实时日志行
function addRealtimeLog(content, level = '') {
    const container = document.getElementById('logContent');

    // 如果是第一条日志，清空loading信息
    if (container.children.length === 1 && container.children[0].classList.contains('loading')) {
        container.innerHTML = '';
    }

    // 限制日志行数
    if (realtimeLogCount >= MAX_REALTIME_LOGS) {
        const lastChild = container.lastChild;
        if (lastChild) {
            container.removeChild(lastChild);
            realtimeLogCount--;
        }
    }

    // 创建日志行元素
    const logLine = document.createElement('div');
    logLine.className = 'log-line realtime-log-line new ' + getLogLineClass(content);
    logLine.textContent = new Date().toLocaleTimeString() + ' ' + content;

    // 添加到容器首行
    container.prepend(logLine);
    realtimeLogCount++;

    document.getElementById('resultStats').textContent = `接收到实时日志共 ${realtimeLogCount} 行`;

    // 移除new类（用于动画效果）
    setTimeout(() => {
        logLine.classList.remove('new');
    }, 300);
}

// 清空实时日志
function clearRealtimeLogs() {
    const container = document.getElementById('logContent');
    container.innerHTML = '<div class="loading">暂无日志内容</div>';
    document.getElementById('fileInfo').textContent = ` (正在监控)`;
    realtimeLogCount = 0;
    document.getElementById('resultStats').textContent = `接收到实时日志共 ${realtimeLogCount} 行`;
}