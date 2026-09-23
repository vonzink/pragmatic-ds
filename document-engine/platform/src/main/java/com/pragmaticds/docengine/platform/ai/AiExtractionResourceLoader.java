package com.pragmaticds.docengine.platform.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class AiExtractionResourceLoader {

    private AiExtractionResourceLoader() {}

    static String read(String path) {
        try (InputStream input = AiExtractionResourceLoader.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing AI extraction resource");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Unable to load AI extraction resource", unreadable);
        }
    }
}
