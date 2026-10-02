package org.aicancode.devblog.ai;

/** Writes a TL;DR of a post. Throwing is allowed: the service then falls back to the template. */
@FunctionalInterface
public interface Summarizer {

    String summarize(String title, String body) throws Exception;
}
