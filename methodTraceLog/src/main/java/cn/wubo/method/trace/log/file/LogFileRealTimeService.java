package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.MethodTraceLogProperties;
import cn.wubo.method.trace.log.file.dto.LogLineInfo;
import cn.wubo.method.trace.log.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static cn.wubo.method.trace.log.file.Constants.MESSAGE;

@Slf4j
public class LogFileRealTimeService implements InitializingBean, DisposableBean {

    private final MethodTraceLogProperties.FileProperties properties;
    private final Pattern logPattern;

    private final SimpMessagingTemplate messagingTemplate;

    // 文件监控服务
    private WatchService watchService;

    // 线程池
    private ScheduledExecutorService executorService;

    // 存储每个文件的读取位置
    private final Map<String, Long> filePositions = new ConcurrentHashMap<>();

    // 当前监控的文件
    private volatile String currentMonitorFile;

    // 监控状态
    private volatile boolean monitoring = false;

    public LogFileRealTimeService(MethodTraceLogProperties.FileProperties properties, SimpMessagingTemplate messagingTemplate) {
        this.properties = properties;
        this.logPattern = Pattern.compile(properties.getLogPattern());
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 初始化方法，在属性设置完成后执行
     * 该方法负责初始化文件监控服务和线程池，并启动文件监控
     *
     * @throws Exception 如果初始化过程中发生错误则抛出异常
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        try {
            // 初始化文件监控服务和线程池
            this.watchService = FileSystems.getDefault().newWatchService();
            this.executorService = Executors.newScheduledThreadPool(2);

            // 注册日志目录监控
            Path logPath = Paths.get(properties.getLogPath());
            if (!Files.exists(logPath)) {
                throw new IllegalStateException("Log directory does not exist: " + properties.getLogPath());
            }

            logPath.register(this.watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

            // 启动文件监控线程
            this.executorService.submit(this::watchFiles);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }


    /**
     * 监控文件系统变化的主循环方法
     *
     * 该方法持续监听文件系统事件，当检测到监控目录中的文件发生变化时，
     * 会触发相应的处理逻辑。方法会在独立线程中运行，直到线程被中断或
     * 监控服务失效时才会退出。
     *
     * 无参数
     *
     * 无返回值
     */
    private void watchFiles() {
        // 持续监听文件变化，直到线程被中断
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 获取下一个可用的监控键
                WatchKey key = watchService.take();

                // 处理所有待处理的事件
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // 跳过溢出事件
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    // 将事件转换为路径事件并获取文件名
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();

                    // 检查是否为当前正在监控的文件，并触发延迟处理
                    if (monitoring && fileName.toString().equals(currentMonitorFile)) {

                        // 延迟处理，避免文件正在写入
                        executorService.schedule(() -> processFileChange(currentMonitorFile), 100, TimeUnit.MILLISECONDS);
                    }
                }

                // 重置监控键，如果重置失败则退出循环
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            } catch (InterruptedException e) {
                // 恢复中断状态并退出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 记录异常日志并关闭监控服务
                log.error(e.getMessage(), e);
                this.close();
            }
        }
    }


    private void processFileChange(String fileName) {
        try {
            // 安全检查：防止路径遍历攻击和非法文件名
            FileUtils.pathInspection(fileName);

            File file = new File(properties.getLogPath(), fileName);
            if (!file.exists()) {
                return;
            }

            long currentLength = file.length();
            long lastPosition = filePositions.getOrDefault(fileName, 0L);

            // 如果文件被截断（如日志轮转），重置位置
            if (currentLength < lastPosition) {
                lastPosition = 0L;
            }

            // 如果有新内容
            if (currentLength > lastPosition) {
                String newContent = readNewContent(file, lastPosition, currentLength);
                if (newContent != null && !newContent.trim().isEmpty()) {
                    // 解析新日志行
                    String[] lines = newContent.split("\n");
                    for (String line : lines) {
                        String trimmedLine = line.trim();
                        if (!trimmedLine.isEmpty()) {
                            sendLogLine(fileName, trimmedLine);
                        }
                    }
                }

                // 更新文件位置
                filePositions.put(fileName, currentLength);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 从指定文件的指定位置读取内容
     *
     * @param file 文件对象
     * @param startPosition 开始读取位置
     * @param endPosition 结束读取位置
     * @return 读取到的内容字符串，UTF-8编码，读取失败返回null
     */
    private String readNewContent(File file, long startPosition, long endPosition) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(startPosition);

            // 计算需要读取的长度，限制单次读取大小为1MB
            long length = endPosition - startPosition;
            if (length > 1024 * 1024) { // 限制单次读取大小为1MB
                length = 1024 * 1024;
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


    /**
     * 发送日志行到WebSocket客户端
     *
     * @param fileName 日志文件名
     * @param logLine 日志行内容
     */
    private void sendLogLine(String fileName, String logLine) {
        try {
            // 解析日志行
            LogLineInfo lineInfo = LogLineInfo.parse(logLine,logPattern);

            // 构建消息
            Map<String, Object> message = Map.of("type", "new_log_line", "fileName", fileName, "content", logLine);

            // 发送到WebSocket客户端
            messagingTemplate.convertAndSend("/topic/log-monitor", message);

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }


    /**
     * 开始监控指定的日志文件
     *
     * @param fileName 要监控的文件名
     */
    public Map<String, Object> startMonitoring(String fileName) throws FileNotFoundException {
        // 安全检查：防止路径遍历攻击和非法文件名
        FileUtils.pathInspection(fileName);

        currentMonitorFile = fileName;
        monitoring = true;

        // 初始化文件读取位置
        File file = new File(properties.getLogPath(), fileName);
        if (file.exists() && file.isFile()) {
            // 使用同步块确保线程安全
            synchronized (filePositions) {
                filePositions.put(fileName, file.length());
            }

            // 确保所有初始化操作成功后再设置监控状态
            currentMonitorFile = fileName;
            monitoring = true;

            return Map.of("type", "monitor_started", "fileName", fileName, MESSAGE, "开始监控日志文件: " + fileName);
        }else
            throw new FileNotFoundException(fileName);
    }


    /**
     * 停止日志监控功能
     *
     * 该方法用于停止当前正在进行的日志文件监控，包括：
     * 1. 设置监控状态为停止
     * 2. 清空当前监控的文件引用
     * 3. 向前端发送监控停止的通知消息
     */
    public Map<String, Object> stopMonitoring(String fileName) {
        monitoring = false;
        currentMonitorFile = null;
        return Map.of("type", "monitor_stopped", MESSAGE, "已停止日志监控:" + fileName);
    }


    /**
     * 获取监控状态信息
     *
     * @return 包含监控状态的Map对象，包含以下键值对：
     *         - "monitoring": 布尔值，表示是否正在监控
     *         - "currentFile": 字符串，当前监控的文件路径，如果未监控任何文件则返回空字符串
     *         - "monitoredFiles": 整数，表示被监控的文件数量
     */
    public Map<String, Object> getMonitorStatus() {
        return Map.of("type", "monitor_status","monitoring", monitoring, "currentFile", currentMonitorFile != null ? currentMonitorFile : "", "monitoredFiles", filePositions.size());
    }


    /**
     * 销毁资源并停止监控
     *
     * @throws Exception 当关闭过程中发生错误时抛出
     */
    @Override
    public void destroy() throws Exception {
        // 停止监控并关闭资源
        monitoring = false;
        this.close();
    }


    /**
     * 关闭资源方法
     *
     * 该方法用于安全地关闭监听服务和执行服务资源，避免资源泄露。
     * 首先尝试关闭文件监听服务，如果关闭过程中出现异常会记录警告日志；
     * 然后关闭线程池执行服务，确保所有相关资源得到 proper cleanup。
     */
    private void close() {
        // 关闭文件监听服务
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception closeException) {
                log.warn("Failed to close watch service", closeException);
            }
        }
        // 关闭线程池执行服务
        if (executorService != null) {
            executorService.shutdown();
        }
    }

}
