package com.pragmaticds.docengine.platform.ai;

import java.util.List;

/**
 * Provider-neutral input for one document extraction. Text-first; {@code pageImages} (Phase F) is
 * the optional pixel escalation — null or empty means text-only, which is every request until the
 * page-image gate is on AND a page's OCR actually failed its floor. Adapters that do not support
 * image input ignore the field (they remain text-only); the Vertex Gemini adapter attaches them.
 */
public record AiExtractionRequest(
        AiDocumentType documentType,
        String documentText,
        String tableStructureText,
        String outputSchemaJson,
        List<PageImage> pageImages) {

    /** The pre-Phase-F shape: text only. */
    public AiExtractionRequest(
            AiDocumentType documentType,
            String documentText,
            String tableStructureText,
            String outputSchemaJson) {
        this(documentType, documentText, tableStructureText, outputSchemaJson, List.of());
    }

    /**
     * One rendered page, as the PNG bytes of the engine's own render blob — the same pixels the
     * reviewer sees. {@code packagePageIndex} lets the prompt name which page each image is, so a
     * value read off an image still reports a page the anchoring can check.
     */
    public record PageImage(int packagePageIndex, byte[] png) {}
}
