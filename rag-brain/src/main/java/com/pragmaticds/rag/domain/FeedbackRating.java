package com.pragmaticds.rag.domain;

/** A thumbs rating on an answer. Stored as VARCHAR(8) in rag_answer_feedback.rating. */
public enum FeedbackRating {
    UP, DOWN;

    public static boolean isValid(String raw) {
        if (raw == null) return false;
        for (FeedbackRating r : values()) {
            if (r.name().equals(raw)) return true;
        }
        return false;
    }

    /** @throws IllegalArgumentException if raw is not exactly UP or DOWN. */
    public static FeedbackRating parse(String raw) {
        if (!isValid(raw)) {
            throw new IllegalArgumentException("Unknown feedback rating: " + raw);
        }
        return valueOf(raw);
    }
}
