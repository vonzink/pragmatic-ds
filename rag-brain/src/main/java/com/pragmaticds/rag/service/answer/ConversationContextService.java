package com.pragmaticds.rag.service.answer;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an anaphoric follow-up ("how do I use it") into a standalone query
 * by prepending the most recent prior USER question, so both retrieval and the
 * answer model carry the topic the pronoun refers to.
 *
 * <p>Each ask is otherwise answered in isolation — the pipeline injects no
 * conversation history — which is why a bare follow-up retrieves nothing and
 * gets refused. This service is the minimal, deterministic (no extra LLM call)
 * bridge: it fires ONLY when the current question contains an anaphor and a
 * prior question exists. A self-contained question, or the first turn of a
 * conversation, is returned byte-for-byte unchanged so the single-turn path is
 * completely untouched.
 */
@Service
public class ConversationContextService {

    /**
     * Whole-word anaphors that signal the question leans on earlier context.
     * Curated to the pronoun senses common in dashboard follow-ups; matched as
     * whole words only (so "commit"/"district" never trigger on "it").
     */
    private static final Set<String> ANAPHORS = Set.of(
            "it", "its", "it's", "that", "this", "they", "them",
            "their", "those", "these", "one", "ones", "same");

    private static final Pattern WORD = Pattern.compile("[a-z0-9']+");

    /**
     * @param question           the current user question (returned as-is when
     *                           null/blank, self-contained, or no prior exists)
     * @param priorUserQuestions earlier user questions in this conversation,
     *                           oldest→newest; only the most recent is used
     */
    public String contextualize(String question, List<String> priorUserQuestions) {
        if (question == null || question.isBlank() || !isFollowUp(question)) {
            return question;
        }
        String prior = mostRecentNonBlank(priorUserQuestions);
        if (prior == null) {
            return question;
        }
        return prior.strip() + " " + question.strip();
    }

    private static boolean isFollowUp(String question) {
        Matcher matcher = WORD.matcher(question.toLowerCase(Locale.US));
        while (matcher.find()) {
            if (ANAPHORS.contains(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static String mostRecentNonBlank(List<String> priorUserQuestions) {
        if (priorUserQuestions == null) {
            return null;
        }
        for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
            String q = priorUserQuestions.get(i);
            if (q != null && !q.isBlank()) {
                return q;
            }
        }
        return null;
    }
}
