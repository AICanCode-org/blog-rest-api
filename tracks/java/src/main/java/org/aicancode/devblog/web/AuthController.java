package org.aicancode.devblog.web;

import java.util.Map;
import org.aicancode.devblog.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.aicancode.devblog.domain.Validation;

/**
 * /auth, /me and the admin role endpoint. The Authorization header is read explicitly and verified by
 * AuthService: one design for all three tracks, no hidden filter chain (see spec/adr/0005).
 */
@RestController
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Object body) {
        return ResponseEntity.status(201).body(Json.user(auth.register(body)));
    }

    @PostMapping("/api/v1/auth/login")
    public Map<String, Object> login(@RequestBody(required = false) Object body) {
        return Json.tokens(auth.login(body));
    }

    @PostMapping("/api/v1/auth/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) Object body) {
        return Json.tokens(auth.refresh(body));
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) Object body) {
        auth.logout(body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.user(auth.me(auth.authenticate(authorization)));
    }

    @PatchMapping("/api/v1/admin/users/{id}/role")
    public Map<String, Object> changeRole(@PathVariable String id, @RequestBody(required = false) Object body,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        var principal = auth.authenticate(authorization);
        return Json.user(auth.changeRole(principal, Validation.pathId(id, "user"), body));
    }
}
