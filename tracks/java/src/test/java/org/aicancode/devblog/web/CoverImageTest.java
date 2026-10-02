package org.aicancode.devblog.web;

import static org.aicancode.devblog.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.Validation;
import org.aicancode.devblog.support.TestApp;
import org.aicancode.devblog.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** v1.1 (Phase 7): optional cover image URL on posts (V2 migration). */
class CoverImageTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://img.example/cover.png", "http://localhost:8080/a.jpg?w=800#top", "HTTPS://CDN.EXAMPLE"})
    void validCoverUrls(String url) {
        assertThat(Validation.coverImageUrl(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "javascript:alert(1)", "data:image/png;base64,AAAA", "ftp://x.example/a.png", "/relative.png",
        "https://exa mple.com", "https://"})
    void invalidCoverUrlsAre400(String url) {
        assertThat(statusOf(() -> Validation.coverImageUrl(url))).containsExactly(400, "validation-error");
    }

    @Test
    void tooLongOrNotAStringIs400AndNullMeansNoCover() {
        assertThatThrownBy(() -> Validation.coverImageUrl("https://x.example/" + "a".repeat(2040)))
                .isInstanceOf(ApiException.class).hasMessageContaining("coverImageUrl");
        assertThatThrownBy(() -> Validation.coverImageUrl(42)).isInstanceOf(ApiException.class);
        assertThat(Validation.coverImageUrl(null)).isNull();
    }

    @Test
    void coverOnCreatePatchClearAndInTheFeed() {
        TestApp app = new TestApp();
        JsonNode author = app.user("AUTHOR", null);
        JsonNode post = app.post(author, Map.of("coverImageUrl", "https://img.example/a.png"));
        assertThat(post.get("coverImageUrl").asString()).isEqualTo("https://img.example/a.png");
        assertThat(app.get("/api/v1/posts").json().get("items").get(0).get("coverImageUrl").asString())
                .isEqualTo("https://img.example/a.png");
        String url = "/api/v1/posts/" + post.get("slug").asString();
        Res r = app.call("PATCH", url, Map.of("version", 1, "coverImageUrl", "https://img.example/b.png"), author);
        assertThat(r.json().get("coverImageUrl").asString()).isEqualTo("https://img.example/b.png");
        r = app.call("PATCH", url, Map.of("version", 2, "title", "Only the title"), author);
        assertThat(r.json().get("coverImageUrl").asString()).isEqualTo("https://img.example/b.png");
        Map<String, Object> clear = new HashMap<>();
        clear.put("version", 3);
        clear.put("coverImageUrl", null);
        r = app.call("PATCH", url, clear, author);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("coverImageUrl").isNull()).isTrue();
        r = app.call("PATCH", url, Map.of("version", 4, "coverImageUrl", "javascript:alert(1)"), author);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void nothingToUpdateMentionsTheCover() {
        TestApp app = new TestApp();
        JsonNode author = app.user("AUTHOR", null);
        JsonNode post = app.post(author);
        Res r = app.call("PATCH", "/api/v1/posts/" + post.get("slug").asString(), Map.of("version", 1), author);
        assertThat(r.json().get("detail").asString())
                .isEqualTo("nothing to update: send title, body, tags, status or coverImageUrl");
    }
}
