package org.aicancode.devblog.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.FeedQuery;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.TagCount;

/** Persistence port for posts and tags. */
public interface PostRepository {

    /** Throws DuplicateException when the slug is taken. */
    Post insertPost(Post post);

    Optional<Post> findPost(String slug);

    /** Empty when the version no longer matches (someone saved in between). */
    Optional<Post> updatePost(Post post, int expectedVersion);

    void softDeletePost(long id, Instant at);

    List<Post> feed(FeedQuery query);

    List<TagCount> tagCounts();
}
