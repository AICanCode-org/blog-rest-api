package org.aicancode.devblog.service;

import java.time.Clock;
import java.util.List;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.TagCount;
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
        // TODO(phase-3 M3): published posts: everyone; drafts: only the author and ADMINs.
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.canSee");
    }

    public static boolean canEdit(Principal principal, Post post) {
        // TODO(phase-3 M3): the author or an ADMIN.
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.canEdit");
    }

    public Post create(Principal principal, Object payload) {
        // TODO(phase-3 M3): AUTHOR/ADMIN only; validate title, body, tags, status; slug = slugify(title); INSERT
        // and on DuplicateError retry with -2, -3 ... (MAX_SLUG_ATTEMPTS); publishedAt = now when created as
        // published.
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.create");
    }

    public Post get(Principal principal, String slug) {
        // TODO(phase-3 M3): a missing post OR a draft you may not see -> 404 (never 403: don't confirm it exists).
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.get");
    }

    public Post update(Principal principal, String slug, Object payload) {
        // TODO(phase-3 M3): order matters (the tests check it): object, version, nothing-to-update (400), get
        // (404), can_edit (403), validate title/body/tags/status, save with optimistic locking (409
        // version-conflict). First publish sets publishedAt; the slug never changes.
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.update");
    }

    public void delete(Principal principal, String slug) {
        // TODO(phase-3 M3): get + can_edit, then a SOFT delete (deleted_at). Deleted posts are 404 from then on.
        throw new UnsupportedOperationException("TODO(phase-3 M3): PostService.delete");
    }

    public Page feed(String limit, String cursor, String tag, String q) {
        // TODO(phase-3 M4): validate limit/cursor/tag/q; ask the repository for limit + 1 rows after the cursor
        // (published, not deleted, newest first, id breaks ties); if there is an extra row, nextCursor = cursor of
        // the last row SHOWN. ADR-0002.
        throw new UnsupportedOperationException("TODO(phase-3 M4): PostService.feed");
    }

    public List<TagCount> tags() {
        // TODO(phase-3 M4): tag counts over published, non-deleted posts, sorted by name.
        throw new UnsupportedOperationException("TODO(phase-3 M4): PostService.tags");
    }

    public Summary summary(Principal principal, String slug) {
        // TODO(phase-3 M5): get the post (same visibility rules) and summarise title + body with the
        // SummaryService.
        throw new UnsupportedOperationException("TODO(phase-3 M5): PostService.summary");
    }
}
