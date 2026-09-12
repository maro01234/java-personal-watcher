package com.example.watcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Small single-user store. Writes are serialized and each completed check is atomic. */
@Repository
public class WatchRepository {
    private final String jdbcUrl;

    public WatchRepository(@Value("${watcher.db}") String path) throws Exception {
        Path file = Path.of(path).toAbsolutePath();
        Files.createDirectories(file.getParent());
        jdbcUrl = "jdbc:sqlite:" + file;
        try (var c = connection(); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("""
                CREATE TABLE IF NOT EXISTS targets (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL,
                  selector TEXT NOT NULL, interval_minutes INTEGER NOT NULL,
                  enabled INTEGER NOT NULL DEFAULT 1, next_check INTEGER NOT NULL DEFAULT 0,
                  last_checked INTEGER, last_status TEXT NOT NULL DEFAULT '未巡回',
                  last_error TEXT NOT NULL DEFAULT '', baseline TEXT)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS checks (
                  id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL REFERENCES targets(id),
                  checked_at INTEGER NOT NULL, status TEXT NOT NULL,
                  before_text TEXT NOT NULL, after_text TEXT NOT NULL,
                  error TEXT NOT NULL, unread INTEGER NOT NULL DEFAULT 0)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS checks_target ON checks(target_id, id DESC)");
        }
    }

    private Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public synchronized long add(String name, String url, String selector, int minutes) {
        if (name == null || name.isBlank() || name.length() > 100)
            throw new IllegalArgumentException("名前は1〜100文字で入力してください。");
        if (minutes < 1 || minutes > 10080) throw new IllegalArgumentException("間隔は1〜10080分で指定してください。");
        String cleanUrl = WebPageWatcher.validateUrl(url);
        String css = selector == null ? "" : selector.strip();
        try {
            if (!css.isEmpty()) org.jsoup.Jsoup.parse("<body></body>").select(css);
        } catch (org.jsoup.select.Selector.SelectorParseException e) {
            throw new IllegalArgumentException("CSSセレクターの書式を確認してください。", e);
        }
        try (var c = connection(); var p = c.prepareStatement(
                "INSERT INTO targets(name,url,selector,interval_minutes) VALUES(?,?,?,?)")) {
            p.setString(1, name.strip()); p.setString(2, cleanUrl); p.setString(3, css); p.setInt(4, minutes);
            p.executeUpdate();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT last_insert_rowid()")) { r.next(); return r.getLong(1); }
        } catch (SQLException e) { throw new IllegalStateException("登録できませんでした。", e); }
    }

    public synchronized List<WatchTarget> targets() {
        var result = new ArrayList<WatchTarget>();
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM targets ORDER BY id DESC")) {
            while (r.next()) result.add(new WatchTarget(r.getLong("id"), r.getString("name"), r.getString("url"),
                r.getString("selector"), r.getInt("interval_minutes"), r.getBoolean("enabled"), r.getLong("next_check"),
                r.getObject("last_checked") == null ? null : r.getLong("last_checked"), r.getString("last_status"), r.getString("last_error")));
            return result;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public synchronized void setEnabled(long id, boolean enabled) {
        try (var c = connection(); var p = c.prepareStatement("UPDATE targets SET enabled=?, next_check=0 WHERE id=?")) {
            p.setBoolean(1, enabled); p.setLong(2, id); p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public synchronized String saveResult(WatchTarget target, String text, String error) {
        long now = Instant.now().toEpochMilli();
        try (var c = connection()) {
            c.setAutoCommit(false);
            try {
                String baseline;
                try (var p = c.prepareStatement("SELECT baseline FROM targets WHERE id=?")) {
                    p.setLong(1, target.id());
                    try (var r = p.executeQuery()) { if (!r.next()) throw new SQLException("Target not found"); baseline = r.getString(1); }
                }
                String status = error != null ? "ERROR" : baseline == null ? "INITIAL" : baseline.equals(text) ? "UNCHANGED" : "CHANGED";
                try (var p = c.prepareStatement("INSERT INTO checks(target_id,checked_at,status,before_text,after_text,error,unread) VALUES(?,?,?,?,?,?,?)")) {
                    p.setLong(1, target.id()); p.setLong(2, now); p.setString(3, status);
                    // Only changes and the initial baseline need full snapshots.
                    p.setString(4, "CHANGED".equals(status) ? baseline : "");
                    p.setString(5, "CHANGED".equals(status) || "INITIAL".equals(status) ? text : "");
                    p.setString(6, error == null ? "" : error); p.setBoolean(7, "CHANGED".equals(status)); p.executeUpdate();
                }
                try (var p = c.prepareStatement("UPDATE targets SET last_checked=?,next_check=?,last_status=?,last_error=?,baseline=? WHERE id=?")) {
                    p.setLong(1, now); p.setLong(2, now + target.intervalMinutes() * 60000L);
                    p.setString(3, status); p.setString(4, error == null ? "" : error);
                    p.setString(5, error == null ? text : baseline); p.setLong(6, target.id()); p.executeUpdate();
                }
                c.commit(); return status;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("巡回結果を保存できませんでした。", e); }
    }

    public synchronized List<CheckRecord> history(long targetId) {
        var result = new ArrayList<CheckRecord>();
        try (var c = connection(); var p = c.prepareStatement("SELECT * FROM checks WHERE target_id=? ORDER BY id DESC LIMIT 100")) {
            p.setLong(1, targetId);
            try (var r = p.executeQuery()) {
                while (r.next()) result.add(new CheckRecord(r.getLong("id"), targetId, r.getLong("checked_at"),
                    r.getString("status"), r.getString("before_text"), r.getString("after_text"), r.getString("error"), r.getBoolean("unread")));
            }
            return result;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public synchronized int unreadCount() {
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM checks WHERE unread=1")) {
            r.next(); return r.getInt(1);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public synchronized void markRead(long targetId) {
        try (var c = connection(); var p = c.prepareStatement("UPDATE checks SET unread=0 WHERE target_id=?")) {
            p.setLong(1, targetId); p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
