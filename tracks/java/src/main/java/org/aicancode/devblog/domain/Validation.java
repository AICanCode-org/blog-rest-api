package org.aicancode.devblog.domain;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input validation. Every method returns the normalised value or throws a 400 problem. The limits are the ones
 * in spec/requirements.md and spec/openapi.yaml; the Python and TypeScript tracks use the same rules and messages.
 * Request bodies arrive as plain Jackson values (Map, List, String, Integer, Long, Boolean, null).
 */
public final class Validation {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern TAG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    public static final int MAX_TAGS = 5;
    public static final int MAX_BODY = 50_000;
    public static final int MAX_COMMENT = 2_000;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    private Validation() {
    }

    private static int chars(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Integer or Long, and not a Boolean (Jackson never turns true into a number, but be explicit). */
    private static Long positiveInt(Object value) {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 1) {
            return ((Number) value).longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireObject(Object payload) {
        if (!(payload instanceof Map)) {
            throw ApiException.validation("request body must be a JSON object");
        }
        return (Map<String, Object>) payload;
    }

    public static String email(Object value) {
        // TODO(phase-3 M1): string, trimmed and lower-cased, at most 254 characters, must match EMAIL_RE. Exact
        // messages: see the validation tests.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.email");
    }

    public static String password(Object value) {
        // TODO(phase-3 M1): string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt ignores the
        // rest). Do not trim.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.password");
    }

    public static String displayName(Object value) {
        // TODO(phase-3 M1): string, trimmed, 1..50 characters (code points, not UTF-16 units).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.displayName");
    }

    public static String role(Object value) {
        // TODO(phase-3 M1): one of READER, AUTHOR, ADMIN (exact case).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.role");
    }

    public static String title(Object value) {
        // TODO(phase-3 M1): string, trimmed, 1..200 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.title");
    }

    public static String body(Object value) {
        // TODO(phase-3 M1): string, not blank, at most 50 000 characters. Stored as-is (Markdown): no trimming.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.body");
    }

    public static List<String> tags(Object value) {
        // TODO(phase-3 M1): missing/null -> []. A list of strings; trim + lower-case each, de-duplicate and
        //   sort, then at most 5 and each must match TAG_RE with at most 30 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.tags");
    }

    /** In a create a missing status means "draft"; in a PATCH (fallback null) an explicit null is an error. */
    public static String status(Object value, String fallback) {
        // TODO(phase-3 M1): missing/null -> the default ('draft' on create); otherwise 'draft' or 'published'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.status");
    }

    public static int version(Object value) {
        // TODO(phase-3 M1): a positive integer (booleans are not integers!). Required on PATCH.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.version");
    }

    public static String commentBody(Object value) {
        // TODO(phase-3 M1): string, trimmed, 1..2000 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.commentBody");
    }

    public static Long parentId(Object value) {
        // TODO(phase-3 M1): missing/null -> null; otherwise a positive integer.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.parentId");
    }

    public static int limit(String value) {
        // TODO(phase-3 M1): query string: missing -> 10; ASCII digits only ([0-9]+: isdigit() also accepts other
        // scripts), 1..50.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.limit");
    }

    public static String tagFilter(String value) {
        // TODO(phase-3 M1): query string: missing/empty -> null; otherwise lower-cased and must match TAG_RE.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.tagFilter");
    }

    public static String searchQuery(String value) {
        // TODO(phase-3 M1): query string: trimmed; empty -> null; at most 100 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.searchQuery");
    }

    public static String commentStatus(String value) {
        // TODO(phase-3 M1): query string: missing -> 'held'; otherwise held, visible or rejected.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.commentStatus");
    }

    public static String decision(Object value) {
        // TODO(phase-3 M1): 'approve' or 'reject'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.decision");
    }

    /** A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400. */
    public static long pathId(String value, String what) {
        // TODO(phase-3 M1): path segment: ASCII digits only (at most 18), otherwise a 404 not-found for `what`: an
        // id that cannot exist is not a 400.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.pathId");
    }
}
