package org.aicancode.devblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Models.TagCount;
import org.aicancode.devblog.support.InMemoryStore;
import org.aicancode.devblog.support.MutableClock;
import org.aicancode.devblog.support.People;
import org.aicancode.devblog.support.TestApp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M4: the public feed - keyset pagination, tag filter, search, tag counts (FR-6), in the service and over HTTP. */
class FeedTest {

    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final PostService posts = new PostService(store, clock, new SummaryService(new TemplateSummarizer(), null));
    private final Principal author = People.create(store, clock, "ada", "AUTHOR").get("ada");

    private List<Post> publish(int n, Object... extra) {
        List<Post> created = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofMinutes(1));
            Map<String, Object> body = new HashMap<>(Map.of("title", "Post " + i, "body", "Body number " + i + ".",
                    "status", "published"));
            for (int k = 0; k < extra.length; k += 2) {
                body.put((String) extra[k], extra[k + 1]);
            }
            created.add(posts.create(author, body));
        }
        return created;
    }

    private Post create(String title, String body) {
        return posts.create(author, Map.of("title", title, "body", body, "status", "published"));
    }

    private static List<String> titles(List<Post> items) {
        return items.stream().map(Post::title).toList();
    }

    @Test
    void feedIsNewestFirstAndSkipsDrafts() {
        publish(3);
        posts.create(author, Map.of("title", "Secret draft", "body", "Not yet."));
        PostService.Page page = posts.feed(null, null, null, null);
        assertThat(titles(page.items())).containsExactly("Post 2", "Post 1", "Post 0");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void keysetPaginationWalksEveryPostExactlyOnce() {
        publish(7);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            PostService.Page page = posts.feed("3", cursor, null, null);
            seen.addAll(titles(page.items()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(
                IntStream.iterate(6, i -> i >= 0, i -> i - 1).mapToObj(i -> "Post " + i).toList());
    }

    @Test
    void postsPublishedInTheSameMillisecondAreNotSkipped() {
        // Same publishedAt for all five: the id breaks the tie, so the cursor needs both.
        for (int i = 0; i < 5; i++) {
            create("Same time " + i, "x.");
        }
        PostService.Page first = posts.feed("2", null, null, null);
        PostService.Page second = posts.feed("2", first.nextCursor(), null, null);
        PostService.Page third = posts.feed("2", second.nextCursor(), null, null);
        List<String> all = new ArrayList<>(titles(first.items()));
        all.addAll(titles(second.items()));
        all.addAll(titles(third.items()));
        assertThat(new HashSet<>(all)).hasSize(5);
        assertThat(all).hasSize(5);
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void aNewPostDoesNotShiftTheNextPage() {
        publish(4);
        PostService.Page first = posts.feed("2", null, null, null);
        publish(1, "title", "Breaking news"); // with OFFSET this would repeat "Post 2"
        PostService.Page second = posts.feed("2", first.nextCursor(), null, null);
        assertThat(titles(first.items())).containsExactly("Post 3", "Post 2");
        assertThat(titles(second.items())).containsExactly("Post 1", "Post 0");
    }

    @Test
    void tagFilter() {
        publish(2, "tags", List.of("java"));
        publish(1, "tags", List.of("python"));
        List<Post> page = posts.feed(null, null, "Java", null).items();
        assertThat(page).hasSize(2).allSatisfy(p -> assertThat(p.tags()).containsExactly("java"));
    }

    @Test
    void searchMatchesTitleAndBodyWords() {
        create("Docker for beginners", "Containers explained.");
        create("Kubernetes", "Orchestrate many docker containers.");
        create("Java records", "Immutable data.");
        assertThat(titles(posts.feed(null, null, null, "docker").items()))
                .containsExactlyInAnyOrder("Docker for beginners", "Kubernetes");
        assertThat(titles(posts.feed(null, null, null, "docker containers kubernetes").items())).containsExactly("Kubernetes");
        assertThat(posts.feed(null, null, null, "rust").items()).isEmpty();
    }

    @Test
    void feedValidatesItsParameters() {
        assertThatThrownBy(() -> posts.feed("0", null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> posts.feed(null, "garbage!", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> posts.feed(null, null, "not a tag", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void deletedPostsLeaveTheFeedAndTheTagCounts() {
        List<Post> javaPosts = publish(2, "tags", List.of("java"));
        publish(1, "tags", List.of("python", "java"));
        posts.create(author, Map.of("title", "Draft", "body", "x", "tags", List.of("rust")));
        assertThat(posts.tags()).containsExactly(new TagCount("java", 3), new TagCount("python", 1));
        posts.delete(author, javaPosts.get(0).slug());
        assertThat(posts.tags()).containsExactly(new TagCount("java", 2), new TagCount("python", 1));
        assertThat(posts.feed(null, null, null, null).items()).extracting(Post::slug).doesNotContain(javaPosts.get(0).slug());
    }

    // ---- over HTTP -------------------------------------------------------------------------------
    @Test
    void feedEndpointShapeAndCursor() {
        TestApp app = new TestApp();
        JsonNode ada = app.user("AUTHOR", "Ada");
        for (int i = 0; i < 3; i++) {
            app.clock.advance(Duration.ofMinutes(1));
            app.post(ada, Map.of("title", "Post " + i, "body", "word ".repeat(50), "tags", List.of("java")));
        }
        JsonNode first = app.get("/api/v1/posts?limit=2").json();
        assertThat(first.get("items").get(0).get("title").asString()).isEqualTo("Post 2");
        assertThat(first.get("items").get(1).get("title").asString()).isEqualTo("Post 1");
        JsonNode item = first.get("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "slug", "title", "excerpt", "tags", "author",
                "publishedAt");
        assertThat(item.get("author").toString())
                .isEqualTo("{\"id\":" + ada.get("user").get("id").asLong() + ",\"displayName\":\"Ada\"}");
        assertThat(item.get("excerpt").asString()).hasSizeLessThanOrEqualTo(160).endsWith("...");
        JsonNode second = app.get("/api/v1/posts?limit=2&cursor=" + first.get("nextCursor").asString()).json();
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("title").asString()).isEqualTo("Post 0");
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(app.get("/api/v1/posts?limit=500").status()).isEqualTo(400);
        assertThat(app.get("/api/v1/posts?cursor=%25%25%25").status()).isEqualTo(400);
    }

    @Test
    void tagsEndpoint() {
        TestApp app = new TestApp();
        JsonNode ada = app.user("AUTHOR");
        app.post(ada, Map.of("tags", List.of("java", "spring")));
        app.post(ada, Map.of("tags", List.of("java")));
        assertThat(app.get("/api/v1/tags").json().toString())
                .isEqualTo("{\"items\":[{\"name\":\"java\",\"posts\":2},{\"name\":\"spring\",\"posts\":1}]}");
        assertThat(app.get("/api/v1/posts?tag=spring").json().get("items").get(0).get("tags").toString())
                .isEqualTo("[\"java\",\"spring\"]");
        assertThat(app.get("/api/v1/posts?q=sentences").json().get("items")).isNotEmpty();
    }
}
