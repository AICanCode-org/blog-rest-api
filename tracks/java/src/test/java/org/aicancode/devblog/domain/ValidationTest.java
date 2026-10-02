package org.aicancode.devblog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** M1: every input rule from spec/requirements.md (FR-1, FR-4, FR-7). */
class ValidationTest {

    private static void rejects(ThrowingCallable call) {
        rejects(call, 400);
    }

    private static void rejects(ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(status));
    }

    @Test
    void emailIsTrimmedAndLowerCased() {
        assertThat(Validation.email("  Asha@Example.COM ")).isEqualTo("asha@example.com");
        for (Object bad : Arrays.asList(null, 42, "", "no-at-sign", "a@b", "a b@c.dev", "@x.dev",
                "a@" + "x".repeat(250) + ".dev")) {
            rejects(() -> Validation.email(bad));
        }
    }

    @Test
    void passwordLimitsAreCharactersAndBytes() {
        assertThat(Validation.password("12345678")).isEqualTo("12345678");
        assertThat(Validation.password("x".repeat(72))).isEqualTo("x".repeat(72));
        rejects(() -> Validation.password("1234567"));
        rejects(() -> Validation.password("x".repeat(73)));
        rejects(() -> Validation.password("é".repeat(37))); // 37 characters but 74 bytes: bcrypt only reads 72 bytes
        rejects(() -> Validation.password(null));
    }

    @Test
    void displayNameAndRole() {
        assertThat(Validation.displayName("  Asha  ")).isEqualTo("Asha");
        rejects(() -> Validation.displayName("   "));
        rejects(() -> Validation.displayName("x".repeat(51)));
        assertThat(Validation.role("AUTHOR")).isEqualTo("AUTHOR");
        rejects(() -> Validation.role("author"));
        rejects(() -> Validation.role("ROOT"));
    }

    @Test
    void titleAndBody() {
        assertThat(Validation.title("  Hello  ")).isEqualTo("Hello");
        rejects(() -> Validation.title(""));
        rejects(() -> Validation.title("x".repeat(201)));
        assertThat(Validation.body("  keep *markdown* as is ")).isEqualTo("  keep *markdown* as is ");
        rejects(() -> Validation.body("   "));
        rejects(() -> Validation.body("x".repeat(50_001)));
        rejects(() -> Validation.body(7));
    }

    @Test
    void tagsAreNormalisedDeduplicatedAndSorted() {
        assertThat(Validation.tags(null)).isEmpty();
        assertThat(Validation.tags(List.of(" Java", "spring-boot", "java"))).containsExactly("java", "spring-boot");
        for (Object bad : List.of("java", List.of(1), List.of("a", "b", "c", "d", "e", "f"), List.of("two words"),
                List.of("-java"), List.of("a--b"), List.of("x".repeat(31)))) {
            rejects(() -> Validation.tags(bad));
        }
    }

    @Test
    void statusAndVersion() {
        assertThat(Validation.status(null, "draft")).isEqualTo("draft");
        assertThat(Validation.status("published", "draft")).isEqualTo("published");
        rejects(() -> Validation.status("archived", "draft"));
        rejects(() -> Validation.status(null, null)); // in a PATCH, an explicit null is not "keep the old value"
        assertThat(Validation.version(3)).isEqualTo(3);
        rejects(() -> Validation.version(null));
        rejects(() -> Validation.version(0));
        rejects(() -> Validation.version("1"));
        rejects(() -> Validation.version(true));
        rejects(() -> Validation.version(1.5));
    }

    @Test
    void commentBodyAndParent() {
        assertThat(Validation.commentBody("  nice post  ")).isEqualTo("nice post");
        rejects(() -> Validation.commentBody(" "));
        rejects(() -> Validation.commentBody("x".repeat(2001)));
        assertThat(Validation.parentId(null)).isNull();
        assertThat(Validation.parentId(5)).isEqualTo(5L);
        rejects(() -> Validation.parentId(0));
        rejects(() -> Validation.parentId("5"));
        rejects(() -> Validation.parentId(false));
    }

    @Test
    void feedParameters() {
        assertThat(Validation.limit(null)).isEqualTo(10);
        assertThat(Validation.limit("50")).isEqualTo(50);
        for (String bad : List.of("0", "51", "-1", "ten", "1.5", "²", "\u0661\u0660", "", "99999999999")) {
            rejects(() -> Validation.limit(bad));
        }
        assertThat(Validation.tagFilter(null)).isNull();
        assertThat(Validation.tagFilter("")).isNull();
        assertThat(Validation.tagFilter("Java")).isEqualTo("java");
        rejects(() -> Validation.tagFilter("not a tag"));
        assertThat(Validation.searchQuery("  ")).isNull();
        assertThat(Validation.searchQuery(" docker ")).isEqualTo("docker");
        rejects(() -> Validation.searchQuery("x".repeat(101)));
    }

    @Test
    void adminParameters() {
        assertThat(Validation.commentStatus(null)).isEqualTo("held");
        assertThat(Validation.commentStatus("rejected")).isEqualTo("rejected");
        rejects(() -> Validation.commentStatus("deleted"));
        assertThat(Validation.decision("approve")).isEqualTo("approve");
        rejects(() -> Validation.decision("maybe"));
    }

    @Test
    void bodyMustBeAnObject() {
        assertThat(Validation.requireObject(Map.of("a", 1))).containsEntry("a", 1);
        for (Object bad : Arrays.asList(null, List.of(), "text", 3)) {
            rejects(() -> Validation.requireObject(bad));
        }
    }

    @Test
    void pathIdsThatCannotExistAre404() {
        assertThat(Validation.pathId("42", "comment")).isEqualTo(42L);
        for (String bad : List.of("abc", "-1", "1.0", "9".repeat(19), "²")) {
            rejects(() -> Validation.pathId(bad, "comment"), 404);
        }
    }
}
