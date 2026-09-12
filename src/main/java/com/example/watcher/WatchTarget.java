package com.example.watcher;

public record WatchTarget(long id, String name, String url, String selector,
        int intervalMinutes, boolean enabled, long nextCheck, Long lastChecked,
        String lastStatus, String lastError) {}
