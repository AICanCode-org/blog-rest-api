package org.aicancode.devblog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.aicancode.devblog.domain.Models.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** M1: slugs, excerpts and keyset cursors (FR-5, FR-6). */
class TextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Hello DevBlog|hello-devblog",
        "Café & Code: Part 2!|cafe-code-part-2",
        "'  --Spaces   and---dashes--  '|spaces-and-dashes",
        "!!!|post",
        "数据库|post"})
    void slugify(String title, String slug) {
        assertThat(Text.slugify(title)).isEqualTo(slug);
    }

    @Test
    void slugIsAtMost80CharactersAndNeverEndsWithADash() {
        assertThat(Text.slugify("a".repeat(79) + " b")).isEqualTo("a".repeat(79));
        assertThat(Text.slugify("word ".repeat(40))).hasSizeLessThanOrEqualTo(80).doesNotEndWith("-");
    }

    @Test
    void plainTextDropsMarkdownSymbolsAndCollapsesWhitespace() {
        assertThat(Text.plainText("# Title\n\n**Bold**  and `code`\n> quote")).isEqualTo("Title Bold and code quote");
    }

    @Test
    void excerptIsAtMost160Characters() {
        assertThat(Text.excerpt("Short body.")).isEqualTo("Short body.");
        assertThat(Text.excerpt("word ".repeat(100))).hasSizeLessThanOrEqualTo(160).endsWith("...");
        assertThat(Text.excerpt("x".repeat(160))).isEqualTo("x".repeat(160));
        assertThat(Text.excerpt("x".repeat(161))).isEqualTo("x".repeat(157) + "...");
    }

    @Test
    void cursorRoundTrip() {
        Instant at = Instant.parse("2026-10-02T09:30:00.123Z");
        String cursor = Text.encodeCursor(at, 42);
        assertThat(cursor).doesNotContain("="); // URL-safe, no padding
        assertThat(Text.decodeCursor(cursor)).isEqualTo(new Cursor(at, 42));
        assertThat(Text.decodeCursor(null)).isNull();
        assertThat(Text.decodeCursor("")).isNull();
    }

    @Test
    void badCursorsAre400() {
        String good = Text.encodeCursor(Instant.parse("2026-01-01T00:00:00Z"), 1);
        for (String bad : List.of("not-base64!!", "aGVsbG8", good.substring(0, good.length() - 3),
                b64("2026-10-02T09:30:00Z|1"), b64("2026-13-02T09:30:00.000Z|1"))) {
            assertThatThrownBy(() -> Text.decodeCursor(bad)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(400));
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
