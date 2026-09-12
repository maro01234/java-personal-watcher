package com.example.watcher;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WatcherTest {
    @TempDir Path temp;

    @Test void persistsChangesAndUnreadAcrossRestartWithoutFalseAlertsAfterErrors() throws Exception {
        Path db = temp.resolve("history.db");
        var repo = new WatchRepository(db.toString());
        long id = repo.add("価格", "https://example.com", ".price", 60);
        var target = repo.targets().getFirst();
        assertEquals("INITIAL", repo.saveResult(target, "70000円", null));
        assertEquals("UNCHANGED", repo.saveResult(target, "70000円", null));
        assertEquals(0, repo.unreadCount());
        assertEquals("ERROR", repo.saveResult(target, null, "HTTP 503"));
        assertEquals("UNCHANGED", repo.saveResult(target, "70000円", null));
        assertEquals("CHANGED", repo.saveResult(target, "65000円", null));
        var reopened = new WatchRepository(db.toString());
        assertEquals(1, reopened.unreadCount());
        var change = reopened.history(id).getFirst();
        assertEquals("70000円", change.beforeText());
        assertEquals("65000円", change.afterText());
        assertEquals("UNCHANGED", reopened.saveResult(reopened.targets().getFirst(), "65000円", null));
        reopened.markRead(id); assertEquals(0, reopened.unreadCount());
        reopened.setEnabled(id, false); assertFalse(reopened.targets().getFirst().enabled());
        reopened.setEnabled(id, true); assertEquals(0, reopened.targets().getFirst().nextCheck());
    }

    @Test void selectedTextIgnoresScriptsAndOutsideChangesAndNormalizesSpaces() {
        var first = Jsoup.parse("<body><time>12:00</time><main>  価格   70000円<script>random()</script></main></body>");
        var second = Jsoup.parse("<body><time>13:00</time><main>価格 70000円</main></body>");
        assertEquals("価格 70000円", WebPageWatcher.extract(first, "main"));
        assertEquals(WebPageWatcher.extract(first, "main"), WebPageWatcher.extract(second, "main"));
        assertThrows(IllegalArgumentException.class, () -> WebPageWatcher.extract(second, ".missing"));
        assertThrows(IllegalArgumentException.class, () -> WebPageWatcher.extract(Jsoup.parse("<main></main>"), "main"));
    }

    @Test void rejectsInvalidRegistration() throws Exception {
        var repo = new WatchRepository(temp.resolve("validation.db").toString());
        assertThrows(IllegalArgumentException.class, () -> repo.add("", "https://example.com", "", 60));
        assertThrows(IllegalArgumentException.class, () -> repo.add("a", "file:///etc/passwd", "", 60));
        assertThrows(IllegalArgumentException.class, () -> repo.add("a", "https://example.com", "", 0));
        assertThrows(IllegalArgumentException.class, () -> repo.add("a", "https://example.com", "[", 60));
        assertEquals(0, repo.targets().size());
    }

    @Test void httpFetchDetectsRealChangesAndKeepsBaselineOnHttpFailure() throws Exception {
        var body = new java.util.concurrent.atomic.AtomicReference<>("<main>初版</main>");
        var code = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(code.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var repo = new WatchRepository(temp.resolve("http.db").toString());
            repo.add("テスト", "http://127.0.0.1:" + server.getAddress().getPort(), "main", 1);
            var target = repo.targets().getFirst(); var watcher = new WebPageWatcher();
            assertEquals("INITIAL", repo.saveResult(target, watcher.check(target), null));
            body.set("<main>改訂版</main>");
            assertEquals("CHANGED", repo.saveResult(target, watcher.check(target), null));
            code.set(503); assertThrows(java.io.IOException.class, () -> watcher.check(target));
        } finally { server.stop(0); }
    }

    @Test void schedulerPreventsDuplicateChecksAndHonorsPauseAndNextDueTime() throws Exception {
        var repo = new WatchRepository(temp.resolve("schedule.db").toString());
        long id = repo.add("test", "https://example.com", "", 60);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        Watcher fake = new Watcher() {
            public String type() { return "WEB"; }
            public String check(WatchTarget target) throws Exception {
                calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); return "text";
            }
        };
        var service = new WatchService(repo, List.of(fake));
        try {
            service.checkDue(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(service.checkNow(id)); service.checkDue(); assertEquals(1, calls.get());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (service.isRunning(id) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(service.isRunning(id));
            service.checkDue(); assertEquals(1, calls.get());
            repo.setEnabled(id, false); service.checkDue(); assertEquals(1, calls.get());
            assertEquals("INITIAL", repo.history(id).getFirst().status());
        } finally { release.countDown(); service.close(); }
    }
}
