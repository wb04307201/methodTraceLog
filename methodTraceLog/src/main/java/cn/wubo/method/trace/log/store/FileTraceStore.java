package cn.wubo.method.trace.log.store;

import cn.wubo.method.trace.log.impl.monitor.MethodTraceInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于 JSON 文件的 TraceStore。每个根 trace 序列化为一行 JSON 写入到
 * {@code <path>/<yyyy-MM-dd>/trace-<traceid>-<timestamp>.json}。
 * <p>
 * 维护一个内存索引 {@code traceId → 文件路径} 用于 O(1) 按 traceId 查找。
 * 启动时扫描目录重建索引（轻量级，可选）。
 * <p>
 * 不支持跨节点/分布式场景；只适合单实例把 trace 落到磁盘防止 OOM。
 */
@Slf4j
public class FileTraceStore implements ITraceStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path rootPath;
    private final ObjectMapper mapper;
    private final Map<String, Path> index = new ConcurrentHashMap<>();
    /** root 列表：traceId → 最后一次 save 的 MethodTraceInfo 引用。读多写少，ConcurrentHashMap 够用。 */
    private final Map<String, MethodTraceInfo> recent = new ConcurrentHashMap<>();
    /** traceId → 写入时间（毫秒），用于 LRU 驱逐。 */
    private final Map<String, Long> recentTimestamps = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxTraces;
    private final boolean rebuildOnStart;

    /**
     * 构造方法。立即创建根目录；如 {@code rebuildOnStart=true} 则同步扫描目录重建索引。
     *
     * @param path            根目录
     * @param ttlMillis       过期时长（毫秒），clean() 释放更老文件
     * @param maxTraces       内存中保留的最近根 trace 数量（用于 getRecent / 减少磁盘读）
     * @param rebuildOnStart  启动时是否扫描目录重建索引（可能很慢）
     */
    public FileTraceStore(String path, long ttlMillis, int maxTraces, boolean rebuildOnStart) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("FileTraceStore path must not be blank");
        }
        this.rootPath = Paths.get(path);
        this.ttlMillis = ttlMillis;
        this.maxTraces = Math.max(1, maxTraces);
        this.rebuildOnStart = rebuildOnStart;
        this.mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create FileTraceStore dir: " + rootPath, e);
        }
        if (rebuildOnStart) {
            rebuildIndex();
        }
    }

    @Override
    public void save(MethodTraceInfo root) {
        if (root == null || root.getBefore() == null) {
            return;
        }
        String traceid = root.getBefore().getTraceid();
        long now = System.currentTimeMillis();

        try {
            Path dayDir = rootPath.resolve(LocalDate.now().format(DAY));
            Files.createDirectories(dayDir);
            Path file = dayDir.resolve("trace-" + safe(traceid) + "-" + now + ".json");
            byte[] bytes = mapper.writeValueAsBytes(root);
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            index.put(traceid, file);
            recent.put(traceid, root);
            recentTimestamps.put(traceid, now);
            evictIfNeeded();
        } catch (IOException e) {
            log.warn("FileTraceStore: failed to write trace {}: {}", traceid, e.getMessage());
        }
    }

    @Override
    public MethodTraceInfo getByTraceId(String traceid) {
        if (traceid == null) {
            return null;
        }
        // 优先从内存 recent 拿
        MethodTraceInfo inMem = recent.get(traceid);
        if (inMem != null) {
            return inMem;
        }
        // 否则从磁盘读
        Path file = index.get(traceid);
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(file.toFile(), MethodTraceInfo.class);
        } catch (IOException e) {
            log.warn("FileTraceStore: failed to read trace {}: {}", traceid, e.getMessage());
            return null;
        }
    }

    @Override
    public List<MethodTraceInfo> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 按时间倒序
        List<Map.Entry<String, Long>> entries = new ArrayList<>(recentTimestamps.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed());
        int n = Math.min(limit, entries.size());
        List<MethodTraceInfo> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            MethodTraceInfo info = recent.get(entries.get(i).getKey());
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    @Override
    public void clean(long maxAgeMillis) {
        long effective = maxAgeMillis > 0 ? maxAgeMillis : ttlMillis;
        if (effective <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // 清理内存
        recentTimestamps.entrySet().removeIf(e -> {
            if (now - e.getValue() > effective) {
                recent.remove(e.getKey());
                index.remove(e.getKey());
                return true;
            }
            return false;
        });
        // 清理磁盘
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("trace-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            if (now - Files.getLastModifiedTime(p).toMillis() > effective) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignore) {
                            // best effort
                        }
                    });
        } catch (IOException e) {
            log.warn("FileTraceStore: clean failed: {}", e.getMessage());
        }
    }

    @Override
    public int size() {
        return recent.size();
    }

    private void rebuildIndex() {
        try (Stream<Path> stream = Files.walk(rootPath)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("trace-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            int added = 0;
            int skipped = 0;
            long now = System.currentTimeMillis();
            for (Path f : files) {
                try {
                    MethodTraceInfo info = mapper.readValue(f.toFile(), MethodTraceInfo.class);
                    if (info != null && info.getBefore() != null) {
                        String traceid = info.getBefore().getTraceid();
                        index.put(traceid, f);
                        // 同步填充 recent / recentTimestamps（用文件 mtime 近似"写入时间"），
                        // 否则重启后 getRecent() 会返回空，与内存版语义不一致。
                        // 同时跳过已经超过 ttlMillis 的过期文件，保持与 clean() 一致。
                        long mtime = Files.getLastModifiedTime(f).toMillis();
                        if (ttlMillis <= 0 || now - mtime <= ttlMillis) {
                            recent.put(traceid, info);
                            recentTimestamps.put(traceid, mtime);
                            added++;
                        } else {
                            skipped++;
                        }
                    } else {
                        skipped++;
                    }
                } catch (IOException e) {
                    skipped++;
                    log.warn("FileTraceStore: skip malformed {}: {}", f, e.getMessage());
                }
            }
            // 重建后可能塞入超过 maxTraces 的条目，按写入时间淘汰最旧
            evictIfNeeded();
            log.info("FileTraceStore: rebuilt index, scanned={} added={} skipped={}", files.size(), added, skipped);
        } catch (IOException e) {
            log.warn("FileTraceStore: rebuild index failed: {}", e.getMessage());
        }
    }

    private void evictIfNeeded() {
        if (recent.size() <= maxTraces) {
            return;
        }
        // 淘汰最旧的
        List<Map.Entry<String, Long>> entries = new ArrayList<>(recentTimestamps.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));
        int toRemove = entries.size() - maxTraces;
        for (int i = 0; i < toRemove; i++) {
            String tid = entries.get(i).getKey();
            recent.remove(tid);
            recentTimestamps.remove(tid);
            // 注意：磁盘文件保留，只从内存驱逐（避免被覆盖逻辑误删）
        }
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
