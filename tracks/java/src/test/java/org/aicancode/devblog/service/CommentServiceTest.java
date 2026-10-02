package org.aicancode.devblog.service;

import static org.aicancode.devblog.support.Problems.codeOf;
import static org.aicancode.devblog.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.ai.RuleModerator;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.support.InMemoryStore;
import org.aicancode.devblog.support.MutableClock;
import org.aicancode.devblog.support.People;
import org.junit.jupiter.api.Test;

/** M5: comments, moderation hold and the human moderation queue (FR-7, FR-9). */
class CommentServiceTest {

    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final Map<String, Principal> p = People.create(store, clock, "ada", "AUTHOR", "rita", "READER", "sam", "READER",
            "root", "ADMIN");
    private final PostService posts = new PostService(store, clock, new SummaryService(new TemplateSummarizer(), null));
    private final CommentService comments = new CommentService(store, store, new ModerationService(new RuleModerator(), null),
            clock);
    private final Post post = posts.create(p.get("ada"), Map.of("title", "Hello", "body", "Body.", "status", "published"));

    private static List<Long> ids(List<Comment> list) {
        return list.stream().map(Comment::id).toList();
    }

    private Comment say(String who, String body) {
        return comments.create(p.get(who), post.slug(), Map.of("body", body));
    }

    @Test
    void aCleanCommentIsVisible() {
        Comment c = say("rita", "  Great post!  ");
        assertThat(Arrays.asList(c.status(), c.body(), c.postSlug(), c.authorName(), c.parentId()))
                .containsExactly("visible", "Great post!", "hello", "Rita", null);
        assertThat(List.of(c.moderation().label(), c.moderation().provider())).containsExactly("ok", "rules");
        assertThat(c.createdAt()).isEqualTo(clock.instant());
        assertThat(ids(comments.list(post.slug()))).containsExactly(c.id());
    }

    @Test
    void spamAndToxicCommentsAreHeldNotDeleted() {
        Comment spam = say("rita", "Free money, click here");
        Comment toxic = say("sam", "you idiot");
        assertThat(List.of(spam.status(), toxic.status())).containsExactly("held", "held");
        assertThat(comments.list(post.slug())).isEmpty();
        assertThat(ids(comments.queue(p.get("root"), null))).containsExactly(spam.id(), toxic.id());
    }

    @Test
    void adminDecisionsOverrideTheAi() {
        Comment held = say("rita", "giveaway at https://a.example");
        clock.advance(Duration.ofMinutes(3));
        Comment approved = comments.decide(p.get("root"), held.id(), Map.of("decision", "approve"));
        assertThat(approved.status()).isEqualTo("visible");
        assertThat(approved.moderatedBy()).isEqualTo(p.get("root").userId());
        assertThat(approved.moderatedAt()).isEqualTo(clock.instant());
        assertThat(approved.moderation().label()).isEqualTo("spam"); // the AI verdict is kept
        assertThat(ids(comments.list(post.slug()))).containsExactly(held.id());
        Comment rejected = comments.decide(p.get("root"), held.id(), Map.of("decision", "reject"));
        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(comments.list(post.slug())).isEmpty();
        assertThat(ids(comments.queue(p.get("root"), "rejected"))).containsExactly(held.id());
    }

    @Test
    void onlyAdminsModerate() {
        Comment held = say("rita", "click here");
        assertThat(statusOf(() -> comments.queue(p.get("ada"), null))).containsExactly(403, "forbidden");
        assertThat(statusOf(() -> comments.decide(p.get("ada"), held.id(), Map.of("decision", "approve"))))
                .containsExactly(403, "forbidden");
        assertThat(codeOf(() -> comments.decide(p.get("root"), 999, Map.of("decision", "approve")))).isEqualTo(404);
        assertThat(codeOf(() -> comments.decide(p.get("root"), held.id(), Map.of("decision", "delete")))).isEqualTo(400);
        assertThat(codeOf(() -> comments.queue(p.get("root"), "all"))).isEqualTo(400);
    }

    @Test
    void repliesAreOneLevelDeepOnTheSamePost() {
        Comment top = say("rita", "Question?");
        Comment reply = comments.create(p.get("ada"), post.slug(), Map.of("body", "Answer.", "parentId", top.id()));
        assertThat(reply.parentId()).isEqualTo(top.id());
        assertThat(codeOf(() -> comments.create(p.get("sam"), post.slug(), Map.of("body", "deeper", "parentId", reply.id()))))
                .isEqualTo(400);
        Post other = posts.create(p.get("ada"), Map.of("title", "Other", "body", "B.", "status", "published"));
        assertThat(codeOf(() -> comments.create(p.get("sam"), other.slug(), Map.of("body", "wrong post", "parentId", top.id()))))
                .isEqualTo(400);
        Comment held = say("sam", "click here");
        assertThat(codeOf(() -> comments.create(p.get("rita"), post.slug(), Map.of("body", "re", "parentId", held.id()))))
                .isEqualTo(400);
        assertThat(codeOf(() -> comments.create(p.get("rita"), post.slug(), Map.of("body", "re", "parentId", 999))))
                .isEqualTo(400);
    }

    @Test
    void commentsNeedAPublishedPost() {
        Post draft = posts.create(p.get("ada"), Map.of("title", "Draft", "body", "B."));
        assertThat(statusOf(() -> comments.create(p.get("ada"), draft.slug(), Map.of("body", "hi"))))
                .containsExactly(404, "not-found");
        assertThat(codeOf(() -> comments.list(draft.slug()))).isEqualTo(404);
        assertThat(codeOf(() -> comments.create(p.get("ada"), "missing", Map.of("body", "hi")))).isEqualTo(404);
        assertThat(codeOf(() -> comments.create(p.get("ada"), "hello", Map.of("body", "")))).isEqualTo(400);
    }

    @Test
    void deleteOwnCommentOrAsAdmin() {
        Comment mine = say("rita", "first");
        Comment theirs = say("sam", "second");
        assertThat(statusOf(() -> comments.delete(p.get("rita"), theirs.id()))).containsExactly(403, "forbidden");
        comments.delete(p.get("rita"), mine.id());
        comments.delete(p.get("root"), theirs.id());
        assertThat(comments.list(post.slug())).isEmpty();
        assertThat(codeOf(() -> comments.delete(p.get("rita"), mine.id()))).isEqualTo(404);
    }

    @Test
    void commentsOfADeletedPostLeaveTheQueue() {
        say("rita", "click here");
        posts.delete(p.get("ada"), post.slug());
        assertThat(comments.queue(p.get("root"), null)).isEmpty();
    }
}
