package com.pragmaticds.rag.service.answer;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PromptQuestionContextService {

    /**
     * Appends user-provided facts to the prompt question as labelled context.
     * Returns the question unchanged when there are no usable facts, so the
     * default no-facts path stays byte-for-byte identical.
     */
    public String appendFacts(String question, Map<String, String> facts) {
        Map<String, String> safe = sanitizeFacts(facts);
        if (safe.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder(question);
        sb.append("\n\nDetails the user already provided (treat as the user's stated"
                + " context, not as source-of-truth facts; still ground every claim in"
                + " the approved sources above):");
        safe.forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(v));
        return sb.toString();
    }

    /**
     * Trims, de-blanks, length-caps (key 60 / value 200 chars), strips newlines,
     * and limits to 10 entries so a hostile or oversized facts map cannot bloat
     * or restructure the prompt.
     */
    private static Map<String, String> sanitizeFacts(Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : facts.entrySet()) {
            if (out.size() >= 10) {
                break;
            }
            String key = e.getKey() == null ? null : e.getKey().strip();
            String value = e.getValue() == null ? null : e.getValue().strip();
            if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
                continue;
            }
            key = key.replaceAll("[\\r\\n]+", " ");
            value = value.replaceAll("[\\r\\n]+", " ");
            if (key.length() > 60) {
                key = key.substring(0, 60);
            }
            if (value.length() > 200) {
                value = value.substring(0, 200);
            }
            out.put(key, value);
        }
        return out;
    }
}
