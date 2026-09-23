package com.pragmaticds.rag.domain;

/** Who gave the rating. Stored as VARCHAR(16) in rag_answer_feedback.source. */
public enum FeedbackSource {
    END_USER, ADMIN;

    public static boolean isValid(String raw) {
        if (raw == null) return false;
        for (FeedbackSource s : values()) {
            if (s.name().equals(raw)) return true;
        }
        return false;
    }

    /** @throws IllegalArgumentException if raw is not exactly END_USER or ADMIN. */
    public static FeedbackSource parse(String raw) {
        if (!isValid(raw)) {
            throw new IllegalArgumentException("Unknown feedback source: " + raw);
        }
        return valueOf(raw);
    }
}
