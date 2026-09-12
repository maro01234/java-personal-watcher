package com.example.watcher;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WatcherApplication implements AppShellConfigurator {
    public static void main(String[] args) {
        SpringApplication.run(WatcherApplication.class, args);
    }
}
