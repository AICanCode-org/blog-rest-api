package org.aicancode.devblog.domain;

import java.time.Instant;
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
        // TODO(phase-3 M1): NFKD-normalise, drop non-ASCII, lower-case, runs of anything but [a-z0-9] -> one '-',
        // trim '-', cut to 80 and trim '-' again; empty -> 'post'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.slugify");
    }

    /** Drop the most common markdown symbols and collapse whitespace. */
    public static String plainText(String markdown) {
        // TODO(phase-3 M1): drop the characters # * ` > and collapse whitespace (then trim). Used by excerpts and
        // summaries.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.plainText");
    }

    public static String shorten(String text, int maxLen) {
        // TODO(phase-3 M1): at most `limit` characters (code points): if longer, cut to limit-3 (no trailing space)
        // and add '...'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.shorten");
    }

    public static String excerpt(String body) {
        // TODO(phase-3 M1): shorten(plain_text(body), 160).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.excerpt");
    }

    public static String encodeCursor(Instant publishedAt, long id) {
        // TODO(phase-3 M1): base64url WITHOUT padding of 'YYYY-MM-DDTHH:MM:SS.sssZ|id' (milliseconds, UTC).
        // ADR-0002.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.encodeCursor");
    }

    public static Cursor decodeCursor(String cursor) {
        // TODO(phase-3 M1): missing/empty -> null; anything that does not decode to exactly that format -> 400
        // 'cursor is invalid'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Text.decodeCursor");
    }
}
