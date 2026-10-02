package org.aicancode.devblog.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Times;
import org.aicancode.devblog.service.AuthService;
import org.aicancode.devblog.service.PostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Posts, the feed, summaries and tags. Thin: read the request, call a service, shape the JSON. */
@RestController
public class PostController {

    private static final String AUTH = "Authorization";
    private final AuthService auth;
    private final PostService posts;
    private final java.time.Clock clock;

    public PostController(AuthService auth, PostService posts, java.time.Clock clock) {
        this.auth = auth;
        this.posts = posts;
        this.clock = clock;
    }

    @GetMapping("/api/v1/posts")
    public Map<String, Object> feed(@RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String q) {
        PostService.Page page = posts.feed(limit, cursor, tag, q);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(Json::postItem).toList());
        body.put("nextCursor", page.nextCursor());
        return body;
    }

    @PostMapping("/api/v1/posts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Object body,
                                                      @RequestHeader(value = AUTH, required = false) String authorization) {
        Post post = posts.create(auth.authenticate(authorization), body);
        return ResponseEntity.created(URI.create("/api/v1/posts/" + post.slug())).body(Json.post(post));
    }

    @GetMapping("/api/v1/posts/{slug}")
    public Map<String, Object> get(@PathVariable String slug,
                                   @RequestHeader(value = AUTH, required = false) String authorization) {
        return Json.post(posts.get(auth.optional(authorization), slug));
    }

    @PatchMapping("/api/v1/posts/{slug}")
    public Map<String, Object> update(@PathVariable String slug, @RequestBody(required = false) Object body,
                                      @RequestHeader(value = AUTH, required = false) String authorization) {
        return Json.post(posts.update(auth.authenticate(authorization), slug, body));
    }

    @DeleteMapping("/api/v1/posts/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug,
                                       @RequestHeader(value = AUTH, required = false) String authorization) {
        posts.delete(auth.authenticate(authorization), slug);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/posts/{slug}/summary")
    public Map<String, Object> summary(@PathVariable String slug,
                                       @RequestHeader(value = AUTH, required = false) String authorization) {
        PostService.Summary s = posts.summary(auth.optional(authorization), slug);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", s.post().slug());
        body.put("provider", s.result().provider());
        body.put("fallback", s.result().fallback());
        body.put("summary", s.result().summary());
        body.put("generatedAt", Times.iso(Times.now(clock)));
        return body;
    }

    @GetMapping("/api/v1/tags")
    public Map<String, Object> tags() {
        return Map.of("items", posts.tags().stream().map(Json::tag).toList());
    }
}
