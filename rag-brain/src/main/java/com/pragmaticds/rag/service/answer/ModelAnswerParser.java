package com.pragmaticds.rag.service.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.service.ai.ModelAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelAnswerParser {

    private static final Logger log = LoggerFactory.getLogger(ModelAnswerParser.class);

    private final ObjectMapper objectMapper;

    public ModelAnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Models sometimes wrap JSON in markdown fences or add prose around it;
     * extract the outermost JSON object before parsing.
     */
    public ModelAnswer parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = content.strip();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(json.substring(start, end + 1), ModelAnswer.class);
        } catch (Exception e) {
            log.error("Failed to parse model answer JSON: {}", e.getMessage());
            return null;
        }
    }
}
