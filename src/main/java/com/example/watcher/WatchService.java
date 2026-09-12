package com.example.watcher;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WatchService {
    private static final Logger log = LoggerFactory.getLogger(WatchService.class);
    private final WatchRepository repository;
    private final Map<String, Watcher> watchers;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());

    public WatchService(WatchRepository repository, List<Watcher> watchers) {
        this.repository = repository;
        this.watchers = watchers.stream().collect(Collectors.toMap(Watcher::type, w -> w));
    }

    @Scheduled(fixedDelayString="${watcher.scheduler-delay-ms:10000}", initialDelay=10000)
    public void checkDue() {
        long now = System.currentTimeMillis();
        repository.targets().stream().filter(t -> t.enabled() && t.nextCheck() <= now).forEach(t -> checkNow(t.id()));
    }

    public boolean isRunning(long id) { return running.contains(id); }

    public boolean checkNow(long id) {
        if (!running.add(id)) return false;
        try {
            executor.submit(() -> {
                try {
                    var target = repository.targets().stream().filter(t -> t.id() == id).findFirst().orElse(null);
                    if (target == null || !target.enabled()) return;
                    String text = null, error = null;
                    try { text = watchers.get("WEB").check(target); }
                    catch (Exception e) { error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                    String status = repository.saveResult(target, text, error);
                    if ("CHANGED".equals(status)) log.info("変更検知: {} (id={})", target.name(), id);
                } catch (Exception e) { log.error("巡回結果の処理に失敗 (id={})", id, e); }
                finally { running.remove(id); }
            });
            return true;
        } catch (RejectedExecutionException e) { running.remove(id); return false; }
    }

    @PreDestroy public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
