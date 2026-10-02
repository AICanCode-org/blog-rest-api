package org.aicancode.devblog.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aicancode.devblog.domain.DuplicateException;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.FeedQuery;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.RefreshToken;
import org.aicancode.devblog.domain.Models.TagCount;
import org.aicancode.devblog.domain.Models.User;
import org.aicancode.devblog.repo.CommentRepository;
import org.aicancode.devblog.repo.PostRepository;
import org.aicancode.devblog.repo.UserRepository;

/**
 * In-memory adapters for unit tests: one object implements all three ports, like one database holds all tables.
 * It behaves like PostgreSQL where it matters (unique e-mail/slug, version check, soft delete). Full-text search
 * is plain word matching here; the integration tests cover the real, stemming search.
 */
public class InMemoryStore implements UserRepository, PostRepository, CommentRepository {

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    public final Map<Long, User> users = new LinkedHashMap<>();
    public final Map<Long, RefreshToken> tokens = new LinkedHashMap<>();
    public final Map<Long, Post> posts = new LinkedHashMap<>();
    public final Set<Long> deletedPosts = new HashSet<>();
    public final Map<Long, Comment> comments = new LinkedHashMap<>();
    public final Set<Long> deletedComments = new HashSet<>();
    public volatile boolean healthy = true;
    private long userIds;
    private long tokenIds;
    private long postIds;
    private long commentIds;

    // ---- users + refresh tokens ----
    @Override
    public synchronized User insertUser(String email, String passwordHash, String displayName, String role, Instant at) {
        if (users.values().stream().anyMatch(u -> u.email().equals(email))) {
            throw new DuplicateException("email");
        }
        User user = new User(++userIds, email, passwordHash, displayName, role, at);
        users.put(user.id(), user);
        return user;
    }

    @Override
    public synchronized Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(u -> u.email().equals(email)).findFirst();
    }

    @Override
    public synchronized Optional<User> findUser(long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized Optional<User> updateRole(long id, String role) {
        User user = users.get(id);
        if (user == null) {
            return Optional.empty();
        }
        users.put(id, user.withRole(role));
        return Optional.of(users.get(id));
    }

    /** Test helper: change a role directly, like an admin would. */
    public void setRole(long id, String role) {
        updateRole(id, role);
    }

    @Override
    public synchronized void insertRefreshToken(long userId, String tokenHash, Instant at, Instant expiresAt) {
        long id = ++tokenIds;
        tokens.put(id, new RefreshToken(id, userId, tokenHash, at, expiresAt, null));
    }

    @Override
    public synchronized Optional<RefreshToken> findRefreshToken(String tokenHash) {
        return tokens.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public synchronized boolean revokeRefreshToken(long id, Instant at) {
        RefreshToken t = tokens.get(id);
        if (t == null || t.revokedAt() != null) {
            return false;
        }
        tokens.put(id, new RefreshToken(t.id(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), at));
        return true;
    }

    @Override
    public synchronized void revokeAllRefreshTokens(long userId, Instant at) {
        for (RefreshToken t : List.copyOf(tokens.values())) {
            if (t.userId() == userId && t.revokedAt() == null) {
                revokeRefreshToken(t.id(), at);
            }
        }
    }

    @Override
    public boolean ping() {
        return healthy;
    }

    // ---- posts ----
    private Post withAuthor(Post p) {
        return new Post(p.id(), p.slug(), p.authorId(), users.get(p.authorId()).displayName(), p.title(), p.body(),
                p.status(), List.copyOf(p.tags()), p.version(), p.createdAt(), p.updatedAt(), p.publishedAt());
    }

    @Override
    public synchronized Post insertPost(Post post) {
        if (posts.values().stream().anyMatch(p -> p.slug().equals(post.slug()))) {
            throw new DuplicateException("slug");
        }
        Post stored = new Post(++postIds, post.slug(), post.authorId(), "", post.title(), post.body(), post.status(),
                post.tags().stream().sorted().toList(), 1, post.createdAt(), post.updatedAt(), post.publishedAt());
        posts.put(stored.id(), stored);
        return withAuthor(stored);
    }

    @Override
    public synchronized Optional<Post> findPost(String slug) {
        return posts.values().stream().filter(p -> p.slug().equals(slug) && !deletedPosts.contains(p.id()))
                .findFirst().map(this::withAuthor);
    }

    @Override
    public synchronized Optional<Post> updatePost(Post post, int expectedVersion) {
        Post current = posts.get(post.id());
        if (current == null || deletedPosts.contains(post.id()) || current.version() != expectedVersion) {
            return Optional.empty();
        }
        Post stored = new Post(post.id(), post.slug(), post.authorId(), "", post.title(), post.body(), post.status(),
                post.tags().stream().sorted().toList(), current.version() + 1, current.createdAt(), post.updatedAt(),
                post.publishedAt());
        posts.put(post.id(), stored);
        return Optional.of(withAuthor(stored));
    }

    @Override
    public synchronized void softDeletePost(long id, Instant at) {
        deletedPosts.add(id);
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Override
    public synchronized List<Post> feed(FeedQuery query) {
        Set<String> wanted = query.q() == null ? Set.of() : words(query.q());
        Comparator<Post> newestFirst = Comparator.comparing(Post::publishedAt).thenComparingLong(Post::id).reversed();
        return posts.values().stream()
                .filter(p -> "published".equals(p.status()) && !deletedPosts.contains(p.id()))
                .filter(p -> query.tag() == null || p.tags().contains(query.tag()))
                .filter(p -> wanted.isEmpty() || words(p.title() + " " + p.body()).containsAll(wanted))
                .filter(p -> query.after() == null || p.publishedAt().isBefore(query.after().publishedAt())
                        || p.publishedAt().equals(query.after().publishedAt()) && p.id() < query.after().id())
                .sorted(newestFirst)
                .limit(query.limit())
                .map(this::withAuthor)
                .toList();
    }

    @Override
    public synchronized List<TagCount> tagCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (Post p : posts.values()) {
            if ("published".equals(p.status()) && !deletedPosts.contains(p.id())) {
                p.tags().forEach(t -> counts.merge(t, 1L, Long::sum));
            }
        }
        List<TagCount> out = new ArrayList<>();
        counts.forEach((name, n) -> out.add(new TagCount(name, n)));
        out.sort(Comparator.comparingLong(TagCount::posts).reversed().thenComparing(TagCount::name));
        return out;
    }

    // ---- comments ----
    private Comment view(Comment c) {
        return new Comment(c.id(), c.postId(), posts.get(c.postId()).slug(), c.authorId(),
                users.get(c.authorId()).displayName(), c.parentId(), c.body(), c.status(), c.moderation(), c.createdAt(),
                c.moderatedBy(), c.moderatedAt());
    }

    @Override
    public synchronized Comment insertComment(Comment c) {
        Comment stored = new Comment(++commentIds, c.postId(), c.postSlug(), c.authorId(), "", c.parentId(), c.body(),
                c.status(), c.moderation(), c.createdAt(), c.moderatedBy(), c.moderatedAt());
        comments.put(stored.id(), stored);
        return view(stored);
    }

    @Override
    public synchronized Optional<Comment> findComment(long id) {
        Comment c = comments.get(id);
        return c == null || deletedComments.contains(id) ? Optional.empty() : Optional.of(view(c));
    }

    private List<Comment> live() {
        return comments.values().stream().filter(c -> !deletedComments.contains(c.id()))
                .sorted(Comparator.comparing(Comment::createdAt).thenComparingLong(Comment::id)).toList();
    }

    @Override
    public synchronized List<Comment> visibleComments(long postId) {
        return live().stream().filter(c -> c.postId() == postId && "visible".equals(c.status())).map(this::view).toList();
    }

    @Override
    public synchronized List<Comment> commentsByStatus(String status) {
        return live().stream().filter(c -> c.status().equals(status) && !deletedPosts.contains(c.postId()))
                .map(this::view).toList();
    }

    @Override
    public synchronized Optional<Comment> setCommentStatus(long id, String status, long by, Instant at) {
        Comment c = comments.get(id);
        if (c == null || deletedComments.contains(id)) {
            return Optional.empty();
        }
        comments.put(id, new Comment(c.id(), c.postId(), c.postSlug(), c.authorId(), c.authorName(), c.parentId(),
                c.body(), status, c.moderation(), c.createdAt(), by, at));
        return findComment(id);
    }

    @Override
    public synchronized void softDeleteComment(long id, Instant at) {
        deletedComments.add(id);
    }
}
