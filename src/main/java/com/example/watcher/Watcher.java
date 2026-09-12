package com.example.watcher;

/** Strategy: add further implementations for price, file or server monitoring. */
public interface Watcher {
    String type();
    String check(WatchTarget target) throws Exception;
}
