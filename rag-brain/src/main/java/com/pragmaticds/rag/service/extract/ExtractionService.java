package com.pragmaticds.rag.service.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.ExtractorConfig;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.DocInput;
import com.pragmaticds.rag.service.analyze.DocumentBlockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single-document, schema-guarded value extraction — the sibling to
 * {@link com.pragmaticds.rag.service.analyze.AnalysisService} but scoped to one
 * document and one extractor's fixed value/meta key manifest (no retrieval, no
 * markdown report, no multi-doc caps).
 *
 * NEVER throws for a document, LLM, or parse failure — an ERROR-status result is
 * returned instead (the FolderBrainPort "never throws" philosophy applied
 * server-side). The one exception is an unknown extractor slug
 * ({@link ExtractorNotFoundException}): that is a caller/routing bug, not a
 * document/model problem, so it throws and maps to 404 at the controller.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final DomainPackRegistry registry;
    private final ModelRouterService router;
    private final DocumentBlockService documentBlockService;
    private final ObjectMapper objectMapper;
    private final int maxOutputTokens;

    public ExtractionService(DomainPackRegistry registry,
                             ModelRouterService router,
                             DocumentBlockService documentBlockService,
                             ObjectMapper objectMapper,
                             @Value("${ragbrain.rag.extract.max-output-tokens:2000}") int maxOutputTokens) {
        this.registry = registry;
        this.router = router;
        this.documentBlockService = documentBlockService;
        this.objectMapper = objectMapper;
        this.maxOutputTokens = maxOutputTokens;
    }

    public ExtractionResult extract(UUID brainId, String slug, DocInput doc) {
        DomainPack pack = registry.bundle(brainId).pack();
        ExtractorConfig extractor = pack.extractors().stream()
                .filter(e -> e.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new ExtractorNotFoundException(slug));

        DocumentBlockService.DocBlock block = documentBlockService.buildOne(doc);
        if (block == null) {
            log.warn("Extract doc build failed for {} ({}): unsupported or unreadable", slug, doc.fileName());
            return error("unsupported or unreadable document: " + doc.fileName(), null, null, 0, 0);
        }

        String prompt = extractor.basePrompt()
                + "\n\nReturn ONLY the JSON object described above — no markdown fences, no commentary, "
                + "no text before or after the JSON.";

        ModelRouterService.RoutedResponse routed;
        try {
            routed = router.generate(
                    // Extractors carry no provider, so an unpaired model is ignored by the
                    // router — same effective behaviour as before the pairing rule existed.
                    AiRequest.forAnalysis(prompt, List.of(block.media()), maxOutputTokens,
                            null, extractor.modelOverride()),
                    brainId);
        } catch (RuntimeException e) {
            log.error("Extract model call failed for {}: {}", slug, e.getMessage());
            return error("model provider error: " + e.getMessage(), null, null, 0, 0);
        }

        int inTok = nz(routed.response().promptTokens());
        int outTok = nz(routed.response().completionTokens());
        String provider = routed.response().providerName();
        String model = routed.response().modelName();

        JsonNode root = extractJson(routed.response().content());
        if (root == null || !root.isObject()) {
            return error("unparseable model response", provider, model, inTok, outTok);
        }

        return parse(extractor, root, provider, model, inTok, outTok);
    }

    /** Keeps only manifest keys (typed per value/meta), dropping and warning on everything else. */
    private ExtractionResult parse(ExtractorConfig extractor, JsonNode root,
                                   String provider, String model, int inTok, int outTok) {
        Set<String> valueKeys = Set.copyOf(extractor.valueKeys());
        Set<String> metaKeys = Set.copyOf(extractor.metaKeys());

        List<String> warnings = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> field : root.properties()) {
            String key = field.getKey();
            JsonNode node = field.getValue();
            if (valueKeys.contains(key)) {
                Double num = coerceNumeric(node);
                if (num == null) {
                    warnings.add("could not parse numeric value for \"" + key + "\"");
                } else {
                    values.put(key, num);
                }
            } else if (metaKeys.contains(key)) {
                if (node != null && !node.isNull()) {
                    values.put(key, node.asText());
                }
            } else {
                warnings.add("unexpected key \"" + key + "\" ignored");
            }
        }

        return new ExtractionResult(ExtractionResult.Status.SUCCESS, values, warnings,
                provider, model, inTok, outTok, null);
    }

    private ExtractionResult error(String reason, String provider, String model, int inTok, int outTok) {
        return new ExtractionResult(ExtractionResult.Status.ERROR, Map.of(), List.of(),
                provider, model, inTok, outTok, reason);
    }

    /** Strips markdown code fences if present, then extracts the outermost {...} object. */
    private JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : s.substring(3);
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.strip();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Failed to parse extract JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Legacy tolerance: a numeric literal as-is, or a "$1,234"/"1,234"-style string. */
    private static Double coerceNumeric(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            String cleaned = node.asText().replace("$", "").replace(",", "").trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(cleaned);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
