package org.aicancode.devblog.ai;

/** A moderation answer: label ok | spam | toxic, confidence 0..1 and a short reason. */
public record Verdict(String label, double confidence, String reason) {
}
