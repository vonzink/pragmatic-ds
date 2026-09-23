package com.pragmaticds.docengine.platform.ai;

/** Loads the static, cacheable W-2 instructions and synthetic example. */
final class W2Prompt {
    private static final String SYSTEM_RESOURCE = "/ai/prompt/w2.system.md";
    private static final String FEW_SHOT_RESOURCE = "/ai/prompt/w2.few-shot.json";

    private final String systemInstructions = AiExtractionResourceLoader.read(SYSTEM_RESOURCE);
    private final String fewShot = AiExtractionResourceLoader.read(FEW_SHOT_RESOURCE);

    String cachedPrefix(String schemaJson) {
        return systemInstructions
                + "\n\nSYNTHETIC EXAMPLE OUTPUT:\n"
                + fewShot
                + "\n\nSTRICT OUTPUT SCHEMA:\n"
                + schemaJson;
    }
}
