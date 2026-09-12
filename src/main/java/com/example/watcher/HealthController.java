package com.example.watcher;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping(value="/healthz", produces="text/plain")
    public String health() { return "ok"; }
}
