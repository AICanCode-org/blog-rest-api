package org.aicancode.devblog.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
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
        if (!(value instanceof String s)) {
            throw ApiException.validation("email is required");
        }
        String normalised = s.strip().toLowerCase(Locale.ROOT);
        if (normalised.length() > 254 || !EMAIL.matcher(normalised).matches()) {
            throw ApiException.validation("email must be a valid e-mail address");
        }
        return normalised;
    }

    public static String password(Object value) {
        if (!(value instanceof String s) || chars(s) < 8 || s.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.validation("password must be 8 characters to 72 bytes");
        }
        return s;
    }

    public static String displayName(Object value) {
        if (!(value instanceof String s) || chars(s.strip()) < 1 || chars(s.strip()) > 50) {
            throw ApiException.validation("displayName must be 1-50 characters");
        }
        return s.strip();
    }

    public static String role(Object value) {
        if (!(value instanceof String s) || !Models.ROLES.contains(s)) {
            throw ApiException.validation("role must be one of READER, AUTHOR, ADMIN");
        }
        return s;
    }

    public static String title(Object value) {
        if (!(value instanceof String s) || chars(s.strip()) < 1 || chars(s.strip()) > 200) {
            throw ApiException.validation("title must be 1-200 characters");
        }
        return s.strip();
    }

    public static String body(Object value) {
        if (!(value instanceof String s) || s.isBlank() || chars(s) > MAX_BODY) {
            throw ApiException.validation("body must be 1-50000 characters");
        }
        return s;
    }

    public static List<String> tags(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw ApiException.validation("tags must be an array of strings");
        }
        TreeSet<String> cleaned = new TreeSet<>();
        for (Object t : list) {
            cleaned.add(((String) t).strip().toLowerCase(Locale.ROOT));
        }
        if (cleaned.size() > MAX_TAGS) {
            throw ApiException.validation("at most 5 tags");
        }
        for (String t : cleaned) {
            if (t.length() > 30 || !TAG.matcher(t).matches()) {
                throw ApiException.validation("tags must be lower-case letters, digits and single hyphens (max 30)");
            }
        }
        return new ArrayList<>(cleaned);
    }

    /** In a create a missing status means "draft"; in a PATCH (fallback null) an explicit null is an error. */
    public static String status(Object value, String fallback) {
        if (value == null && fallback != null) {
            return fallback;
        }
        if (!"draft".equals(value) && !"published".equals(value)) {
            throw ApiException.validation("status must be draft or published");
        }
        return (String) value;
    }

    public static int version(Object value) {
        Long v = positiveInt(value);
        if (v == null || v > Integer.MAX_VALUE) {
            throw ApiException.validation("version is required (the version you read)");
        }
        return v.intValue();
    }

    public static String commentBody(Object value) {
        if (!(value instanceof String s) || chars(s.strip()) < 1 || chars(s.strip()) > MAX_COMMENT) {
            throw ApiException.validation("body must be 1-2000 characters");
        }
        return s.strip();
    }

    public static Long parentId(Object value) {
        if (value == null) {
            return null;
        }
        Long v = positiveInt(value);
        if (v == null) {
            throw ApiException.validation("parentId must be a positive integer");
        }
        return v;
    }

    public static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        if (!DIGITS.matcher(value).matches() || value.length() > 4 || Integer.parseInt(value) < 1
                || Integer.parseInt(value) > MAX_LIMIT) {
            throw ApiException.validation("limit must be an integer 1-50");
        }
        return Integer.parseInt(value);
    }

    public static String tagFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String t = value.strip().toLowerCase(Locale.ROOT);
        if (t.length() > 30 || !TAG.matcher(t).matches()) {
            throw ApiException.validation("tag must be lower-case letters, digits and single hyphens (max 30)");
        }
        return t;
    }

    public static String searchQuery(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (chars(value.strip()) > 100) {
            throw ApiException.validation("q must be at most 100 characters");
        }
        return value.strip();
    }

    public static String commentStatus(String value) {
        if (value == null) {
            return "held";
        }
        if (!List.of("held", "visible", "rejected").contains(value)) {
            throw ApiException.validation("status must be held, visible or rejected");
        }
        return value;
    }

    public static String decision(Object value) {
        if (!"approve".equals(value) && !"reject".equals(value)) {
            throw ApiException.validation("decision must be approve or reject");
        }
        return (String) value;
    }

    /** A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400. */
    public static long pathId(String value, String what) {
        if (value == null || !DIGITS.matcher(value).matches() || value.length() > 18) {
            throw ApiException.notFound(what);
        }
        return Long.parseLong(value);
    }
}
