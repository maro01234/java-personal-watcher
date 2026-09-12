package com.example.watcher;

public record CheckRecord(long id, long targetId, long checkedAt, String status,
        String beforeText, String afterText, String error, boolean unread) {}
