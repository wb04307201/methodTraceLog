/* logs.js — 日志文件 tab
 *  - 移自 logFile.js，全局函数加 logs 前缀避免和别的 tab 冲突
 *  - WS 实时 tail 跟着 tab 显隐启停（onShow init / onHide deactivate）
 */
(function () {
    'use strict';

    let currentFile = null;
    let stompClient = null;
    let isConnected = false;
    let isMonitoring = false;
    let realtimeLogCount = 0;
    let heartbeatTimer = null;
    const MAX_REALTIME_LOGS = 1000;

    let currentQuery = {
        fileName: '', page: 1, pageSize: 100,
        keyword: '', level: '', startTime: null, endTime: null, reverse: true
    };
    let totalPages = 1;

    // ===== 工具 =====
    function getLogLineClass(line) {
        if (line.includes('ERROR')) return 'error';
        if (line.includes('WARN'))  return 'warn';
        if (line.includes('INFO'))  return 'info';
        return '';
    }

    // ===== 文件列表 =====
    function logsLoadFileList() {
        mtlFetch('/methodTraceLog/logFile/files')
            .then(r => r.json())
            .then(files => {
                const el = document.getElementById('fileList');
                if (!files || files.length === 0) { el.innerHTML = '<div class="error">未找到日志文件</div>'; return; }
                el.innerHTML = files.map(file => `
                    <div class="file-item" onclick="window.MTLLogs.selectFile('${MTL.escapeHtml(file.name)}')">
                        <div class="file-name">${MTL.escapeHtml(file.name)}</div>
                        <div class="file-info">
                            <span>${MTL.formatFileSize(file.size)}</span>
                            <span>${MTL.formatDate(file.lastModified)}</span>
                        </div>
                    </div>
                `).join('');
            })
            .catch(e => {
                console.error(e);
                document.getElementById('fileList').innerHTML = '<div class="error">加载文件列表失败</div>';
            });
    }

    function selectFile(fileName) {
        if (isMonitoring) stopRealtimeMonitor();

        document.querySelectorAll('.file-item').forEach(item => item.classList.remove('selected'));
        const target = event.target.closest('.file-item');
        if (target) target.classList.add('selected');

        currentFile = fileName;
        currentQuery.fileName = fileName;

        document.getElementById('searchPanel').classList.remove('hidden');
        logsSearchLogs();
    }

    // 把 fetch 错误转成可读 message:4xx/5xx 优先用后端 JSON.message,降级用 HTTP 状态
    async function parseError(r, fallback) {
        let body = '';
        try { body = await r.text(); } catch (_) { /* ignore */ }
        let msg = fallback;
        if (body) {
            try {
                const j = JSON.parse(body);
                if (j && j.message) msg = j.message;
            } catch (_) {
                if (body.length < 200) msg = body;
            }
        }
        return new Error('HTTP ' + r.status + ': ' + msg);
    }

    // ===== 搜索 =====
    async function logsSearchLogs(page = 1) {
        if (!currentFile) { alert('请先选择日志文件'); return; }
        currentQuery.page = page;
        currentQuery.pageSize = parseInt(document.getElementById('pageSize').value);
        currentQuery.keyword = document.getElementById('keyword').value;
        currentQuery.level = document.getElementById('level').value;
        currentQuery.reverse = document.getElementById('reverse').value === 'true';
        const st = document.getElementById('startTime').value;
        const et = document.getElementById('endTime').value;
        currentQuery.startTime = st ? st + ':00' : null;
        currentQuery.endTime   = et ? et + ':00' : null;

        const contentEl = document.getElementById('logContent');
        contentEl.innerHTML = '<div class="loading">正在搜索日志...</div>';
        document.getElementById('resultPanel').classList.remove('hidden');

        try {
            const r = await mtlFetch('/methodTraceLog/logFile/query', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(currentQuery)
            });
            if (!r.ok) throw await parseError(r, '搜索失败');
            const data = await r.json();
            displayResults(data);
        } catch (e) {
            console.error('[logs]', e);
            // 真实原因给用户看到,而不是一句"搜索日志失败"
            contentEl.innerHTML = '<div class="error"><div class="error__title">搜索失败</div><div class="error__detail">' + MTL.escapeHtml(e.message || String(e)) + '</div></div>';
            MTL.toast && MTL.toast('搜索失败: ' + (e.message || '未知错误'));
        }
    }

    function logsResetLogs() {
        document.getElementById('pageSize').value = 100;
        document.getElementById('keyword').value = '';
        document.getElementById('level').value = '';
        document.getElementById('reverse').value = 'true';
        document.getElementById('startTime').value = '';
        document.getElementById('endTime').value = '';
        logsSearchLogs();
    }

    function displayResults(result) {
        document.getElementById('fileName').textContent = currentFile;
        document.getElementById('fileInfo').textContent = ` (${MTL.formatFileSize(result.fileSize)}, 修改时间: ${MTL.formatDateTime(result.lastModified)})`;
        document.getElementById('resultStats').textContent = `共找到 ${result.totalLines} 行，第 ${result.currentPage}/${result.totalPages} 页`;

        totalPages = result.totalPages;
        document.getElementById('currentPage').value = result.currentPage;
        document.getElementById('totalPages').textContent = result.totalPages;
        document.getElementById('paginationInfo').textContent =
            `显示第 ${(result.currentPage - 1) * currentQuery.pageSize + 1} - ${Math.min(result.currentPage * currentQuery.pageSize, result.totalLines)} 行`;

        document.getElementById('prevBtn').disabled = result.currentPage <= 1;
        document.getElementById('nextBtn').disabled = result.currentPage >= result.totalPages;

        const logContentEl = document.getElementById('logContent');
        if (!result.lines || result.lines.length === 0) { MTL.renderEmpty(logContentEl, { title: '未找到匹配的日志', hint: '试试调整关键字 / 时间范围 / 日志级别', icon: 'search-x' }); return; }
        logContentEl.innerHTML = result.lines.map(line =>
            `<div class="log-line ${getLogLineClass(line)}">${MTL.escapeHtml(line)}</div>`
        ).join('');
    }

    function logsPreviousPage() { if (currentQuery.page > 1) logsSearchLogs(currentQuery.page - 1); }
    function logsNextPage()     { if (currentQuery.page < totalPages) logsSearchLogs(currentQuery.page + 1); }
    function logsGoToPage()     { const p = parseInt(document.getElementById('currentPage').value); if (p >= 1 && p <= totalPages) logsSearchLogs(p); }

    // ===== 下载 =====
    function logsDownloadLog() {
        if (!currentFile) { alert('请先选择日志文件'); return; }
        currentQuery.keyword = document.getElementById('keyword').value;
        currentQuery.level = document.getElementById('level').value;
        const st = document.getElementById('startTime').value;
        const et = document.getElementById('endTime').value;
        currentQuery.startTime = st ? st + ':00' : null;
        currentQuery.endTime   = et ? et + ':00' : null;

        mtlFetch('/methodTraceLog/logFile/download', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(currentQuery)
        })
            .then(r => { if (!r.ok) throw new Error('下载失败'); return r.blob().then(blob => ({ blob, cd: r.headers.get('content-disposition') || '' })); })
            .then(({ blob, cd }) => {
                const m = cd.match(/filename="?(.+?)"?(;|$)/);
                const filename = (m && m[1]) ? m[1] : (currentQuery.fileName || 'downloaded.log');
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(a.href);
            })
            .catch(e => console.error('下载日志失败:', e));
    }

    // ===== WebSocket 实时 tail =====
    function initWebSocket() {
        try {
            const socket = new SockJS('/ws');
            stompClient = new StompJs.Client({
                webSocketFactory: () => socket,
                debug: () => {},
                reconnectDelay: 5000,
                heartbeatIncoming: 4000,
                heartbeatOutgoing: 4000
            });
            stompClient.onConnect = frame => {
                isConnected = true;
                updateConnectionStatus(true);
                stompClient.subscribe('/topic/log-monitor', message => {
                    handleRealtimeMessage(JSON.parse(message.body));
                });
                heartbeatTimer = setInterval(() => {
                    if (isConnected) {
                        stompClient.publish({ destination: '/app/heartbeat', body: JSON.stringify({ message: 'ping' }) });
                    }
                }, 30000);
            };
            stompClient.onStompError = frame => {
                console.error('STOMP 错误:', frame.headers['message'], frame.body);
                isConnected = false;
                updateConnectionStatus(false);
            };
            stompClient.onWebSocketClose = () => {
                isConnected = false;
                updateConnectionStatus(false);
                updateMonitoringStatus(false);
            };
            stompClient.activate();
        } catch (e) {
            console.error('初始化 WebSocket 失败:', e);
            updateConnectionStatus(false);
        }
    }

    function destroyWebSocket() {
        if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
        if (stompClient) {
            try { stompClient.deactivate(); } catch (e) { /* ignore */ }
            stompClient = null;
        }
        isConnected = false;
        isMonitoring = false;
        updateConnectionStatus(false);
    }

    function updateConnectionStatus(connected) {
        isConnected = connected;
        const statusEl = document.getElementById('connectionStatus');
        const textEl   = document.getElementById('connectionText');
        if (!statusEl || !textEl) return;
        if (connected) { statusEl.className = 'status-indicator connected';    textEl.textContent = 'WebSocket已连接'; }
        else            { statusEl.className = 'status-indicator disconnected'; textEl.textContent = 'WebSocket未连接'; }
    }

    function updateMonitoringStatus(monitoring) {
        isMonitoring = monitoring;
        const statusEl  = document.getElementById('connectionStatus');
        const textEl    = document.getElementById('connectionText');
        const toggleBtn = document.getElementById('realtimeToggleBtn');
        const clearBtn  = document.getElementById('clearRealtimeBtn');
        if (!statusEl || !textEl || !toggleBtn) return;
        if (monitoring) {
            statusEl.className = 'status-indicator monitoring';
            textEl.textContent = '正在监控: ' + currentFile;
            toggleBtn.innerHTML = (window.mtlIconHTML ? window.mtlIconHTML('stop-circle', '停止监控') : '停止监控');
            toggleBtn.classList.add('active');
            if (clearBtn) { clearBtn.classList.remove('hidden'); clearBtn.disabled = false; }
        } else {
            statusEl.className = isConnected ? 'status-indicator connected' : 'status-indicator disconnected';
            textEl.textContent   = isConnected ? 'WebSocket已连接' : 'WebSocket未连接';
            toggleBtn.innerHTML = (window.mtlIconHTML ? window.mtlIconHTML('radio', '实时监控') : '实时监控');
            toggleBtn.classList.remove('active');
            if (clearBtn) { clearBtn.disabled = true; }
        }
    }

    function logsToggleRealtimeMonitor() {
        if (!isConnected) { alert('WebSocket 未连接，无法开始监控'); return; }
        if (!currentFile) { alert('请先选择日志文件'); return; }
        if (isMonitoring) stopRealtimeMonitor();
        else              startRealtimeMonitor();
    }

    function startRealtimeMonitor() {
        if (!isConnected || !currentFile) return;
        stompClient.publish({ destination: '/app/start-monitor', body: JSON.stringify({ fileName: currentFile }) });
        clearRealtimeLogs();
        const pagination = document.querySelector('#resultPanel .pagination');
        if (pagination) pagination.style.display = 'none';
    }

    function stopRealtimeMonitor() {
        if (!isConnected) return;
        stompClient.publish({ destination: '/app/stop-monitor', body: JSON.stringify({ fileName: currentFile }) });
        const pagination = document.querySelector('#resultPanel .pagination');
        if (pagination) pagination.style.display = 'flex';
        setTimeout(() => logsSearchLogs(), 500);
    }

    function handleRealtimeMessage(message) {
        switch (message.type) {
            case 'new_log_line':    addRealtimeLog(message.content, message.level); break;
            case 'monitor_started': updateMonitoringStatus(true);  break;
            case 'monitor_stopped': updateMonitoringStatus(false); break;
            case 'error':           addRealtimeLog('错误: ' + message.message, 'error'); break;
            case 'heartbeat':       /* no-op */ break;
            default: console.log('未知消息类型:', message.type);
        }
    }

    function addRealtimeLog(content, level = '') {
        const container = document.getElementById('logContent');
        if (container.children.length === 1 && container.children[0].classList.contains('loading')) {
            container.innerHTML = '';
        }
        if (realtimeLogCount >= MAX_REALTIME_LOGS) {
            const last = container.lastChild;
            if (last) { container.removeChild(last); realtimeLogCount--; }
        }
        const logLine = document.createElement('div');
        logLine.className = 'log-line realtime-log-line new ' + getLogLineClass(content);
        logLine.textContent = new Date().toLocaleTimeString() + ' ' + content;
        container.prepend(logLine);
        realtimeLogCount++;
        document.getElementById('resultStats').textContent = `接收到实时日志共 ${realtimeLogCount} 行`;
        setTimeout(() => logLine.classList.remove('new'), 300);
    }

    function logsClearRealtimeLogs() {
        const container = document.getElementById('logContent');
        container.innerHTML = '<div class="loading">暂无日志内容</div>';
        document.getElementById('fileInfo').textContent = ' (正在监控)';
        realtimeLogCount = 0;
        document.getElementById('resultStats').textContent = `接收到实时日志共 ${realtimeLogCount} 行`;
    }

    // ===== Tab 生命周期 =====
    function onShow() {
        logsLoadFileList();
        // 仅当 WS 还没建过（首次显示 / 之前被 onHide 关掉）才建；登录成功时 onShow 会被再调一次，不能重复建
        if (!stompClient) initWebSocket();
    }
    function onHide() {
        destroyWebSocket();
    }

    document.addEventListener('DOMContentLoaded', () => {
        window.MTLLogs = { selectFile };
        MTL.registerTab('logs', { onShow, onHide });
    });
})();
