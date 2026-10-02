package org.aicancode.devblog.service;

import static org.aicancode.devblog.support.Problems.codeOf;
import static org.aicancode.devblog.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.support.InMemoryStore;
import org.aicancode.devblog.support.MutableClock;
import org.aicancode.devblog.support.People;
import org.junit.jupiter.api.Test;

/** M3: posts - roles, slugs, drafts, ownership, optimistic locking, soft delete (FR-4, FR-5). */
class PostServiceTest {

    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final PostService posts = new PostService(store, clock, new SummaryService(new TemplateSummarizer(), null));
    private final Map<String, Principal> p = People.create(store, clock, "ada", "AUTHOR", "bob", "AUTHOR", "rita", "READER",
            "root", "ADMIN");

    /** POST plus extra fields: post("status", "published"). */
    private static Map<String, Object> post(Object... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("title", "Hello DevBlog", "body", "First post. It has two sentences."));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> patch(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void authorCreatesADraftByDefault() {
        Post post = posts.create(p.get("ada"), post("tags", List.of("Python", "fastapi")));
        assertThat(List.of(post.slug(), post.status(), post.version())).containsExactly("hello-devblog", "draft", 1);
        assertThat(post.tags()).containsExactly("fastapi", "python");
        assertThat(post.authorName()).isEqualTo("Ada");
        assertThat(post.publishedAt()).isNull();
        assertThat(post.createdAt()).isEqualTo(clock.instant()).isEqualTo(post.updatedAt());
    }

    @Test
    void publishingOnCreateSetsPublishedAt() {
        assertThat(posts.create(p.get("ada"), post("status", "published")).publishedAt()).isEqualTo(clock.instant());
    }

    @Test
    void readersCannotWritePosts() {
        assertThat(statusOf(() -> posts.create(p.get("rita"), post()))).containsExactly(403, "forbidden");
        assertThat(posts.create(p.get("root"), post()).authorId()).isEqualTo(p.get("root").userId()); // ADMIN may
    }

    @Test
    void slugCollisionsGetASuffix() {
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slugs.add(posts.create(p.get("ada"), post()).slug());
        }
        assertThat(slugs).containsExactly("hello-devblog", "hello-devblog-2", "hello-devblog-3");
    }

    @Test
    void createValidates() {
        assertThat(codeOf(() -> posts.create(p.get("ada"), Map.of("title", "", "body", "x")))).isEqualTo(400);
        assertThat(codeOf(() -> posts.create(p.get("ada"), post("status", "archived")))).isEqualTo(400);
    }

    @Test
    void draftsAreVisibleOnlyToTheAuthorAndAdmins() {
        Post draft = posts.create(p.get("ada"), post());
        assertThat(posts.get(p.get("ada"), draft.slug()).id()).isEqualTo(draft.id());
        assertThat(posts.get(p.get("root"), draft.slug()).id()).isEqualTo(draft.id());
        // Not 403: a stranger must not even learn that the draft exists.
        for (Principal stranger : Arrays.asList(null, p.get("bob"), p.get("rita"))) {
            assertThat(statusOf(() -> posts.get(stranger, draft.slug()))).containsExactly(404, "not-found");
        }
    }

    @Test
    void publishedPostsArePublic() {
        Post post = posts.create(p.get("ada"), post("status", "published"));
        assertThat(posts.get(null, post.slug()).title()).isEqualTo("Hello DevBlog");
    }

    @Test
    void authorEditsOwnPostAndVersionIncrements() {
        Post post = posts.create(p.get("ada"), post());
        clock.advance(Duration.ofMinutes(5));
        Post updated = posts.update(p.get("ada"), post.slug(), patch("version", 1, "title", "Hello again",
                "tags", List.of("news")));
        assertThat(List.of(updated.title(), updated.version())).containsExactly("Hello again", 2);
        assertThat(updated.tags()).containsExactly("news");
        assertThat(updated.slug()).isEqualTo(post.slug()); // slugs never change: links keep working
        assertThat(updated.updatedAt()).isEqualTo(clock.instant());
        assertThat(updated.createdAt()).isEqualTo(post.createdAt());
    }

    @Test
    void otherAuthorsGet403ButAdminsMayEdit() {
        Post post = posts.create(p.get("ada"), post("status", "published"));
        assertThat(statusOf(() -> posts.update(p.get("bob"), post.slug(), patch("version", 1, "title", "Mine now"))))
                .containsExactly(403, "forbidden");
        assertThat(posts.update(p.get("root"), post.slug(), patch("version", 1, "title", "Moderated")).title())
                .isEqualTo("Moderated");
    }

    @Test
    void staleVersionIsA409Conflict() {
        Post post = posts.create(p.get("ada"), post());
        posts.update(p.get("ada"), post.slug(), patch("version", 1, "body", "Edited in tab one."));
        assertThat(statusOf(() -> posts.update(p.get("ada"), post.slug(), patch("version", 1, "body", "Edited in tab two."))))
                .containsExactly(409, "version-conflict");
    }

    @Test
    void updateNeedsAVersionAndAChange() {
        Post post = posts.create(p.get("ada"), post());
        assertThat(codeOf(() -> posts.update(p.get("ada"), post.slug(), patch("title", "No version")))).isEqualTo(400);
        assertThat(codeOf(() -> posts.update(p.get("ada"), post.slug(), patch("version", 1)))).isEqualTo(400);
        assertThat(codeOf(() -> posts.update(p.get("ada"), post.slug(), patch("version", 1, "status", null)))).isEqualTo(400);
        assertThat(codeOf(() -> posts.update(p.get("ada"), "no-such-post", patch("version", 1, "title", "x")))).isEqualTo(404);
    }

    @Test
    void firstPublicationDateIsKeptWhenRepublishing() {
        Post post = posts.create(p.get("ada"), post());
        clock.advance(Duration.ofHours(1));
        Instant first = posts.update(p.get("ada"), post.slug(), patch("version", 1, "status", "published")).publishedAt();
        assertThat(first).isEqualTo(clock.instant());
        clock.advance(Duration.ofHours(1));
        Post back = posts.update(p.get("ada"), post.slug(), patch("version", 2, "status", "draft"));
        Post again = posts.update(p.get("ada"), post.slug(), patch("version", 3, "status", "published"));
        assertThat(back.publishedAt()).isEqualTo(first);
        assertThat(again.publishedAt()).isEqualTo(first);
    }

    @Test
    void softDeleteHidesThePost() {
        Post post = posts.create(p.get("ada"), post("status", "published"));
        assertThat(statusOf(() -> posts.delete(p.get("bob"), post.slug()))).containsExactly(403, "forbidden");
        posts.delete(p.get("ada"), post.slug());
        assertThat(codeOf(() -> posts.get(p.get("ada"), post.slug()))).isEqualTo(404);
        assertThat(store.posts).containsKey(post.id()); // the row is kept (soft delete)
        assertThat(codeOf(() -> posts.delete(p.get("ada"), post.slug()))).isEqualTo(404);
    }

    @Test
    void adminCanDeleteAnyPost() {
        Post post = posts.create(p.get("ada"), post("status", "published"));
        posts.delete(p.get("root"), post.slug());
        assertThat(codeOf(() -> posts.get(null, post.slug()))).isEqualTo(404);
    }

    @Test
    void summaryUsesTheTemplateOffline() {
        Post post = posts.create(p.get("ada"), post("status", "published"));
        assertThat(posts.summary(null, post.slug()).result())
                .isEqualTo(new SummaryService.Result("template", false, "First post. It has two sentences."));
        Post draft = posts.create(p.get("ada"), post());
        assertThat(codeOf(() -> posts.summary(null, draft.slug()))).isEqualTo(404);
    }
}
