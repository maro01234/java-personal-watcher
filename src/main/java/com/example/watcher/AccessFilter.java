package com.example.watcher;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protect every UI/RPC endpoint when published. Health checks reveal no user data. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessFilter extends OncePerRequestFilter {
    private final byte[] credentials;

    public AccessFilter(@Value("${watcher.auth.required:false}") boolean required,
            @Value("${watcher.auth.password:}") String password,
            @Value("${watcher.auth.password-file:}") String passwordFile) throws IOException {
        String secret = passwordFile.isBlank() ? password : Files.readString(Path.of(passwordFile)).strip();
        if (required && secret.length() < 16)
            throw new IllegalStateException("公開時は16文字以上の監視用パスワードが必要です。");
        credentials = secret.isEmpty() ? null : ("admin:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "same-origin");
        if (credentials == null || ("/healthz".equals(request.getRequestURI()) && "GET".equals(request.getMethod()))) {
            chain.doFilter(request, response); return;
        }
        response.setHeader("Cache-Control", "no-store");
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Basic ")) {
            try {
                if (MessageDigest.isEqual(credentials, Base64.getDecoder().decode(auth.substring(6)))) {
                    chain.doFilter(request, response); return;
                }
            } catch (IllegalArgumentException ignored) { /* Malformed credentials are unauthorized. */ }
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"Personal Watcher\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
