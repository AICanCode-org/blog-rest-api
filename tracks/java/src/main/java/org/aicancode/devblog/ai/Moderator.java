package org.aicancode.devblog.ai;

/** Classifies a comment. Throwing is allowed: the service then falls back to the rules. */
@FunctionalInterface
public interface Moderator {

    Verdict classify(String text) throws Exception;
}
