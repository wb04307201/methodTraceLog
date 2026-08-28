package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.utils.FileUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cn.wubo.method.trace.log.file.Constants.MESSAGE;

/**
 * 日志文件实时 tail 服务：基于 {@link WatchService} 监听日志目录，
 * 把新追加的行通过 STOMP 推送到 {@code /topic/log-monitor}。
 * <p>
 * 与 {@link LogFileService} 互补：后者负责历史查询 / 过滤 / 下载，
 * 本类只负责"最新追加的几行推给前端"。
 * <p>
 * 多文件支持：每个被监控的文件独立维护 lastPosition；同一目录注册一个
 * WatchService，事件循环按文件名过滤命中；{@code startMonitoring(name)}
 * 重复调用不会重启；{@code stopMonitoring(name)} 仅清掉该文件的状态。
 * <p>
 * 关键状态：
 *  - {@link #filePositions}：每个已读文件的字节偏移（截断/轮转时由 processFileChange 重置为 0）
 *  - {@link #monitoredFiles}：当前正在监控的文件集合（多文件）
 *  - 单个 WatchService + 单线程事件循环
 */
@Slf4j
public class LogFileRealTimeService implements InitializingBean, DisposableBean {

    private final MethodTraceLogProperties.FileProperties properties;
    private final SimpMessagingTemplate messagingTemplate;

    private WatchService watchService;
    private ScheduledExecutorService executorService;

    /** 文件名 → 字节偏移 */
    private final Map<String, Long> filePositions = new ConcurrentHashMap<>();

    /** 当前正在监控的文件集合（多文件支持的核心数据结构）。 */
    private final Map<String, MonitoredFile> monitoredFiles = new ConcurrentHashMap<>();

    public LogFileRealTimeService(MethodTraceLogProperties.FileProperties properties, SimpMessagingTemplate messagingTemplate) {
        this.properties = properties;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executorService = Executors.newScheduledThreadPool(2);

        Path logPath = Paths.get(properties.getLogPath());
        if (!Files.exists(logPath)) {
            throw new IllegalStateException("Log directory does not exist: " + properties.getLogPath());
        }

        logPath.register(this.watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

        this.executorService.submit(this::watchFiles);
        log.info("mtl-log-realtime: watching {}", properties.getLogPath());
    }

    private void watchFiles() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    String fileName = ev.context().toString();
                    // 命中任意正在监控的文件 → 延迟读取
                    if (monitoredFiles.containsKey(fileName)) {
                        executorService.schedule(() -> processFileChange(fileName), 100, TimeUnit.MILLISECONDS);
                    }
                }
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                // destroy() 关闭 WatchService 后 take() 会抛此异常；正常退出
                break;
            } catch (Exception e) {
                log.warn("mtl-logmonitor: watch error: {}", e.getMessage());
            }
        }
    }

    private void processFileChange(String fileName) {
        try {
            FileUtils.pathInspection(fileName);
            File file = new File(properties.getLogPath(), fileName);
            if (!file.exists()) {
                return;
            }
            long currentLength = file.length();
            long lastPosition = filePositions.getOrDefault(fileName, 0L);
            if (currentLength < lastPosition) {
                // 文件被截断（轮转）
                lastPosition = 0L;
            }
            if (currentLength > lastPosition) {
                String newContent = readNewContent(file, lastPosition, currentLength);
                if (newContent != null && !newContent.trim().isEmpty()) {
                    String[] lines = newContent.split("\n");
                    for (String line : lines) {
                        String trimmedLine = line.trim();
                        if (!trimmedLine.isEmpty()) {
                            sendLogLine(fileName, trimmedLine);
                        }
                    }
                }
                filePositions.put(fileName, currentLength);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private String readNewContent(File file, long startPosition, long endPosition) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(startPosition);
            long length = endPosition - startPosition;
            if (length > 1024 * 1024L) {
                length = 1024 * 1024L;
            }
            byte[] buffer = new byte[(int) length];
            int bytesRead = raf.read(buffer);
            if (bytesRead > 0) {
                return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    private void sendLogLine(String fileName, String logLine) {
        try {
            Map<String, Object> message = Map.of("type", "new_log_line", "fileName", fileName, "content", logLine);
            messagingTemplate.convertAndSend("/topic/log-monitor", message);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 开始监控指定的日志文件。
     * <p>
     * 同一文件已在监控时不会重复启动（返回 {@code monitor_already_started}）；
     * 多个不同文件可同时被监控，每个独立维护 lastPosition。
     *
     * @param fileName 要监控的文件名（需通过 {@link FileUtils#pathInspection} 白名单）
     * @return 推送回前端的状态消息
     * @throws FileNotFoundException 文件不存在或不是 regular file
     */
    public Map<String, Object> startMonitoring(String fileName) throws FileNotFoundException {
        FileUtils.pathInspection(fileName);
        File file = new File(properties.getLogPath(), fileName);
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException(fileName);
        }
        MonitoredFile mf = new MonitoredFile(fileName);
        MonitoredFile prev = monitoredFiles.putIfAbsent(fileName, mf);
        if (prev != null) {
            return Map.of(
                    "type", "monitor_already_started",
                    "fileName", fileName,
                    MESSAGE, "已在监控日志文件: " + fileName);
        }
        filePositions.put(fileName, file.length());
        log.info("mtl-log-realtime: started watching {}", fileName);
        return Map.of(
                "type", "monitor_started",
                "fileName", fileName,
                MESSAGE, "开始监控日志文件: " + fileName);
    }

    /**
     * 停止监控指定文件。仅清掉该文件的状态，其它仍在监控的文件不受影响。
     * 传入未监控的文件名（或 null / 空字符串）时返回 {@code monitor_not_started}，
     * 不抛异常。
     */
    public Map<String, Object> stopMonitoring(String fileName) {
        String safeName = fileName == null ? "" : fileName;
        MonitoredFile removed = monitoredFiles.remove(safeName);
        filePositions.remove(safeName);
        if (removed == null) {
            return Map.of(
                    "type", "monitor_not_started",
                    "fileName", safeName,
                    MESSAGE, "未监控该日志文件: " + safeName);
        }
        log.info("mtl-log-realtime: stopped watching {}", safeName);
        return Map.of(
                "type", "monitor_stopped",
                "fileName", safeName,
                MESSAGE, "已停止日志监控: " + safeName);
    }

    /**
     * 获取监控状态：返回所有正在监控的文件名列表 + 计数。
     * <p>
     * 旧字段 {@code currentFile} 已移除（多文件场景下不再有"当前文件"的概念）；
     * 用 {@code monitoredFiles}（Set&lt;String&gt;）+ {@code monitoredFilesCount}（int）
     * 取代。前端面板 logs.js 不依赖 {@code currentFile}（它自己用客户端变量），
     * 因此这是无侵入式变更。
     */
    public Map<String, Object> getMonitorStatus() {
        Set<String> files = new TreeSet<>(monitoredFiles.keySet());
        return Map.of(
                "type", "monitor_status",
                "monitoring", !files.isEmpty(),
                "monitoredFiles", files,
                "monitoredFilesCount", files.size());
    }

    /**
     * 当前正在监控的文件数（供测试/状态查询用）。
     */
    int monitoredFileCount() {
        return monitoredFiles.size();
    }

    @Override
    public void destroy() throws Exception {
        monitoredFiles.clear();
        this.close();
    }

    @PreDestroy
    public void close() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception closeException) {
                log.warn("Failed to close watch service", closeException);
            }
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    /**
     * 跟踪一个被监控文件。位置信息复用 {@link #filePositions}，本类仅保留
     * fileName 字段以备将来扩展（如：每文件 WatchKey、每文件过滤规则等）。
     */
    static final class MonitoredFile {
        final String fileName;
        MonitoredFile(String fileName) {
            this.fileName = fileName;
        }
    }
}
