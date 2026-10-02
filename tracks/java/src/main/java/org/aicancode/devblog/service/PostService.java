package org.aicancode.devblog.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.DuplicateException;
import org.aicancode.devblog.domain.Models.FeedQuery;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.TagCount;
import org.aicancode.devblog.domain.Text;
import org.aicancode.devblog.domain.Times;
import org.aicancode.devblog.domain.Validation;
import org.aicancode.devblog.repo.PostRepository;

/** Posts: drafts, publishing, ownership, optimistic locking, soft delete, the feed and tags. */
public class PostService {

    public static final int MAX_SLUG_ATTEMPTS = 50;

    private final PostRepository posts;
    private final Clock clock;
    private final SummaryService summaries;

    public PostService(PostRepository posts, Clock clock, SummaryService summaries) {
        this.posts = posts;
        this.clock = clock;
        this.summaries = summaries;
    }

    public record Page(List<Post> items, String nextCursor) {
    }

    public record Summary(Post post, SummaryService.Result result) {
    }

    public static boolean canSee(Principal principal, Post post) {
        return "published".equals(post.status())
                || principal != null && (principal.isAdmin() || principal.userId() == post.authorId());
    }

    public static boolean canEdit(Principal principal, Post post) {
        return principal.isAdmin() || principal.userId() == post.authorId();
    }

    public Post create(Principal principal, Object payload) {
        AuthService.requireRole(principal, "AUTHOR", "ADMIN");
        Map<String, Object> data = Validation.requireObject(payload);
        String title = Validation.title(data.get("title"));
        String body = Validation.body(data.get("body"));
        List<String> tags = Validation.tags(data.get("tags"));
        String status = Validation.status(data.get("status"), "draft");
        Instant now = Times.now(clock);
        String base = Text.slugify(title);
        for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = attempt == 1 ? base : base + "-" + attempt;
            Post draft = new Post(0, slug, principal.userId(), "", title, body, status, tags, 1, now, now,
                    "published".equals(status) ? now : null);
            try {
                // Insert and let the unique constraint decide: "check, then insert" would race.
                return posts.insertPost(draft);
            } catch (DuplicateException ignored) {
                // slug taken: try the next suffix
            }
        }
        throw ApiException.validation("too many posts with this title");
    }

    public Post get(Principal principal, String slug) {
        Post post = posts.findPost(slug).orElse(null);
        // A draft you may not see is "not found", not "forbidden": don't confirm that it exists.
        if (post == null || !canSee(principal, post)) {
            throw ApiException.notFound("post");
        }
        return post;
    }

    public Post update(Principal principal, String slug, Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        int expected = Validation.version(data.get("version"));
        if (List.of("title", "body", "tags", "status").stream().noneMatch(data::containsKey)) {
            throw ApiException.validation("nothing to update: send title, body, tags or status");
        }
        Post post = get(principal, slug);
        if (!canEdit(principal, post)) {
            throw ApiException.forbidden("only the author or an ADMIN can edit this post");
        }
        // validate in the same order as the other tracks: title, body, tags, status
        String title = data.containsKey("title") ? Validation.title(data.get("title")) : post.title();
        String body = data.containsKey("body") ? Validation.body(data.get("body")) : post.body();
        List<String> tags = data.containsKey("tags") ? Validation.tags(data.get("tags")) : post.tags();
        String status = data.containsKey("status") ? Validation.status(data.get("status"), null) : post.status();
        Instant now = Times.now(clock);
        // first publication sets publishedAt; re-publishing keeps the date
        Instant publishedAt = "published".equals(status) && post.publishedAt() == null ? now : post.publishedAt();
        Post changed = new Post(post.id(), post.slug(), post.authorId(), post.authorName(), title, body, status, tags,
                post.version(), post.createdAt(), now, publishedAt);
        return posts.updatePost(changed, expected).orElseThrow(() -> ApiException.versionConflict(post.version()));
    }

    public void delete(Principal principal, String slug) {
        Post post = get(principal, slug);
        if (!canEdit(principal, post)) {
            throw ApiException.forbidden("only the author or an ADMIN can delete this post");
        }
        posts.softDeletePost(post.id(), Times.now(clock));
    }

    public Page feed(String limit, String cursor, String tag, String q) {
        int size = Validation.limit(limit);
        FeedQuery query = new FeedQuery(size + 1, Validation.tagFilter(tag), Validation.searchQuery(q),
                Text.decodeCursor(cursor));
        List<Post> rows = posts.feed(query);
        // Ask for one extra row: if it exists there is a next page, and the cursor is the last row shown.
        List<Post> items = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            Post last = items.get(items.size() - 1);
            next = Text.encodeCursor(last.publishedAt(), last.id());
        }
        return new Page(List.copyOf(items), next);
    }

    public List<TagCount> tags() {
        return posts.tagCounts();
    }

    public Summary summary(Principal principal, String slug) {
        Post post = get(principal, slug);
        return new Summary(post, summaries.summarize(post.title(), post.body()));
    }
}
