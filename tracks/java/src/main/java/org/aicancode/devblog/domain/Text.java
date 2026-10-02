package org.aicancode.devblog.domain;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aicancode.devblog.domain.Models.Cursor;

/** Slugs, excerpts and keyset cursors (pure functions, identical rules in every track). */
public final class Text {

    public static final int SLUG_MAX = 80;
    public static final int EXCERPT_MAX = 160;
    private static final Pattern MARKUP = Pattern.compile("[#*`>]");
    // (?U): \s means Unicode whitespace, like Python and JavaScript
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final Pattern CURSOR =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3})Z\\|([0-9]{1,18})$");
    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    private Text() {
    }

    /** "Café & Code: Part 2!" -> "cafe-code-part-2". Empty results become "post". */
    public static String slugify(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("[^\\x00-\\x7F]", "")
                .toLowerCase(Locale.ROOT);
        String slug = ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        slug = slug.substring(0, Math.min(slug.length(), SLUG_MAX)).replaceAll("-+$", "");
        return slug.isEmpty() ? "post" : slug;
    }

    /** Drop the most common markdown symbols and collapse whitespace. */
    public static String plainText(String markdown) {
        return SPACES.matcher(MARKUP.matcher(markdown).replaceAll("")).replaceAll(" ").strip();
    }

    public static String shorten(String text, int maxLen) {
        if (text.codePointCount(0, text.length()) <= maxLen) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxLen - 3);
        return text.substring(0, end).stripTrailing() + "...";
    }

    public static String excerpt(String body) {
        return shorten(plainText(body), EXCERPT_MAX);
    }

    public static String encodeCursor(Instant publishedAt, long id) {
        String raw = Times.iso(publishedAt) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            if (!BASE64URL.matcher(cursor).matches()) {
                throw new IllegalArgumentException(cursor);
            }
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = CURSOR.matcher(raw);
            if (!m.matches()) {
                throw new IllegalArgumentException(raw);
            }
            return new Cursor(Instant.parse(m.group(1) + "Z"), Long.parseLong(m.group(2)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw ApiException.validation("cursor is invalid");
        }
    }
}
