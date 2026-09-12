package com.example.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AccessFilterTest {
    private static final String SECRET = "test-password-for-unit-tests";
    @Test void cloudRequiresStrongPassword() {
        assertThrows(IllegalStateException.class, () -> new AccessFilter(true, "", ""));
    }
    @Test void unauthenticatedRequestsCannotAccessUiOrRpc() throws Exception {
        var filter = new AccessFilter(true, SECRET, "");
        for (String uri : new String[]{"/", "/?v-r=uidl", "/VAADIN/foo", "/healthz/"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", uri), response, (req,res) -> fail("Must not pass"));
            assertEquals(401, response.getStatus());
        }
    }
    @Test void healthCheckPassesAndValidCredentialsPass() throws Exception {
        var filter = new AccessFilter(true, SECRET, "");
        var reached = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/healthz"), new MockHttpServletResponse(), (req,res) -> reached.set(true));
        assertTrue(reached.get()); reached.set(false);
        var request = new MockHttpServletRequest("POST", "/");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + SECRET).getBytes(StandardCharsets.UTF_8)));
        filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> reached.set(true));
        assertTrue(reached.get());
    }
    @Test void invalidAndMalformedCredentialsFail() throws Exception {
        var filter = new AccessFilter(true, SECRET, "");
        for (String auth : new String[]{"Basic invalid!", "Basic " + Base64.getEncoder().encodeToString("admin:wrong".getBytes(StandardCharsets.UTF_8))}) {
            var request = new MockHttpServletRequest("GET", "/"); request.addHeader("Authorization", auth);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req,res) -> fail("Must not pass"));
            assertEquals(401, response.getStatus());
        }
    }
}
