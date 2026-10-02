package org.aicancode.devblog.web;

import java.util.Map;
import org.aicancode.devblog.domain.Validation;
import org.aicancode.devblog.service.AuthService;
import org.aicancode.devblog.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Comments and the admin moderation queue. */
@RestController
public class CommentController {

    private static final String AUTH = "Authorization";
    private final AuthService auth;
    private final CommentService comments;

    public CommentController(AuthService auth, CommentService comments) {
        this.auth = auth;
        this.comments = comments;
    }

    @GetMapping("/api/v1/posts/{slug}/comments")
    public Map<String, Object> list(@PathVariable String slug) {
        return Map.of("items", comments.list(slug).stream().map(Json::comment).toList());
    }

    @PostMapping("/api/v1/posts/{slug}/comments")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String slug, @RequestBody(required = false) Object body,
                                                      @RequestHeader(value = AUTH, required = false) String authorization) {
        return ResponseEntity.status(201).body(Json.comment(comments.create(auth.authenticate(authorization), slug, body)));
    }

    @DeleteMapping("/api/v1/comments/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestHeader(value = AUTH, required = false) String authorization) {
        var principal = auth.authenticate(authorization);
        comments.delete(principal, Validation.pathId(id, "comment"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/admin/comments")
    public Map<String, Object> queue(@RequestParam(required = false) String status,
                                     @RequestHeader(value = AUTH, required = false) String authorization) {
        var principal = auth.authenticate(authorization);
        return Map.of("items", comments.queue(principal, status).stream().map(Json::comment).toList());
    }

    @PostMapping("/api/v1/admin/comments/{id}/decision")
    public Map<String, Object> decide(@PathVariable String id, @RequestBody(required = false) Object body,
                                      @RequestHeader(value = AUTH, required = false) String authorization) {
        var principal = auth.authenticate(authorization);
        return Json.comment(comments.decide(principal, Validation.pathId(id, "comment"), body));
    }
}
