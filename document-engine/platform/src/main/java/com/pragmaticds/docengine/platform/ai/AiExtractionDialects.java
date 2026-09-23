package com.pragmaticds.docengine.platform.ai;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * The registry of document types the AI extraction seam speaks. Dialects are stateless and built
 * once at class load — the same eager resource-parse discipline the adapters already had, so a
 * broken schema or prompt resource fails at startup, never mid-request.
 */
public final class AiExtractionDialects {

    private static final Map<AiDocumentType, AiExtractionDialect> BY_TYPE = build();

    private AiExtractionDialects() {}

    /** The dialect for a type; throws for a type no dialect covers — a wiring bug, not data. */
    public static AiExtractionDialect forType(AiDocumentType type) {
        AiExtractionDialect dialect = type == null ? null : BY_TYPE.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("no AI extraction dialect for " + type);
        }
        return dialect;
    }

    public static Collection<AiExtractionDialect> all() {
        return BY_TYPE.values();
    }

    private static Map<AiDocumentType, AiExtractionDialect> build() {
        Map<AiDocumentType, AiExtractionDialect> dialects = new EnumMap<>(AiDocumentType.class);
        dialects.put(AiDocumentType.BANK_STATEMENT, new BankStatementDialect());
        dialects.put(AiDocumentType.PAYSTUB, new PaystubDialect());
        dialects.put(AiDocumentType.W2, new W2Dialect());
        return Map.copyOf(dialects);
    }

    private static final class BankStatementDialect implements AiExtractionDialect {
        private final BankStatementExtractionSchema schema = new BankStatementExtractionSchema();
        private final BankStatementExtractionParser parser =
                new BankStatementExtractionParser(schema);
        private final BankStatementPrompt prompt = new BankStatementPrompt();

        @Override
        public AiDocumentType type() {
            return AiDocumentType.BANK_STATEMENT;
        }

        @Override
        public String schemaJson() {
            return schema.schemaJson();
        }

        @Override
        public String cachedPrefix() {
            return prompt.cachedPrefix(schema.schemaJson());
        }

        @Override
        public AiExtractionResult parse(AiExtractionResult rawResult) {
            return parser.parse(rawResult);
        }
    }

    private static final class PaystubDialect implements AiExtractionDialect {
        private final PaystubExtractionSchema schema = new PaystubExtractionSchema();
        private final PaystubExtractionParser parser = new PaystubExtractionParser(schema);
        private final PaystubPrompt prompt = new PaystubPrompt();

        @Override
        public AiDocumentType type() {
            return AiDocumentType.PAYSTUB;
        }

        @Override
        public String schemaJson() {
            return schema.schemaJson();
        }

        @Override
        public String cachedPrefix() {
            return prompt.cachedPrefix(schema.schemaJson());
        }

        @Override
        public AiExtractionResult parse(AiExtractionResult rawResult) {
            return parser.parse(rawResult);
        }
    }

    private static final class W2Dialect implements AiExtractionDialect {
        private final W2ExtractionSchema schema = new W2ExtractionSchema();
        private final W2ExtractionParser parser = new W2ExtractionParser(schema);
        private final W2Prompt prompt = new W2Prompt();

        @Override
        public AiDocumentType type() {
            return AiDocumentType.W2;
        }

        @Override
        public String schemaJson() {
            return schema.schemaJson();
        }

        @Override
        public String cachedPrefix() {
            return prompt.cachedPrefix(schema.schemaJson());
        }

        @Override
        public AiExtractionResult parse(AiExtractionResult rawResult) {
            return parser.parse(rawResult);
        }
    }
}
