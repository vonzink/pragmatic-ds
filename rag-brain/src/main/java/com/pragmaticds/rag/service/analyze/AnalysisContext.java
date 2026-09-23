package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The non-file half of an analyze request (the `context` multipart part), decoded
 * from JSON. Loan snapshot + org catalog shape the prompt; doc metadata pairs the
 * uploaded bytes with their suite ids. Never persisted.
 *
 * `disablePageFilter` is the analyst's escape hatch: true sends every page of every PDF,
 * bypassing the analyzer's page-selection profile. Absent in JSON ⇒ false (filtering applies).
 */
public record AnalysisContext(
        List<DocMeta> docs,
        String extraInstructions,
        LoanSnapshot loan,
        OrgCatalog org,
        List<AnalystNote> analystNotes,
        boolean disablePageFilter
) {
    /**
     * One entry of the document manifest. The first four fields pair an uploaded part with its
     * suite id; the rest is what the consuming system already knows about the document and wants
     * honoured — rendered onto the document's line in the prompt, which is the data the documents
     * analyzer's ALREADY DECIDED rule reads.
     *
     * <p>Unknown keys are ignored so the suite can add a key before this record learns it. The
     * reverse — a key the prompt promises but the record silently drops — is exactly what this
     * record used to do with {@code assignedDocType} and {@code engineSegments}: the rule was in
     * the prompt, the data never arrived, and the model re-decided every type it was told to keep.
     *
     * @param parsed           true when the document engine has a rendering of this document
     * @param mdContract       the rendering's contract id (e.g. DOCENGINE-MD-1) when parsed
     * @param parsedMarkdown   the engine's rendering ITSELF, carried here rather than as a second
     *                         uploaded part so one document stays one part and one manifest entry.
     *                         It is rendered alongside the attached file, never instead of it: the
     *                         fields are what a machine already read, the pages are the document,
     *                         and a thin extraction must not be able to hide the document behind it
     * @param assignedDocType  a type already decided by a person or the document engine; the
     *                         model reports it unchanged
     * @param assignedBy       who decided it: "human" or "engine"
     * @param engineConfidence the engine's own score for that decision, when the engine made it
     * @param engineSegments   boundaries the engine already found inside a bundle; the model
     *                         returns exactly these
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocMeta(String id, String fileName, String contentType, Long sizeBytes,
                          Boolean parsed, String mdContract, String parsedMarkdown,
                          String assignedDocType, String assignedBy, Double engineConfidence,
                          List<EngineSegment> engineSegments) {

        /** The canonical constructor is the JSON creator; the 4-arg form below is for callers. */
        @JsonCreator
        public DocMeta {
            engineSegments = engineSegments == null ? List.of() : List.copyOf(engineSegments);
        }

        /** The pairing fields only — what every caller before the decided-type manifest sent. */
        public DocMeta(String id, String fileName, String contentType, Long sizeBytes) {
            this(id, fileName, contentType, sizeBytes, null, null, null, null, null, null, null);
        }

        /** True when the consuming system supplied the engine's rendering of this document. */
        public boolean hasParsedMarkdown() {
            return parsedMarkdown != null && !parsedMarkdown.isBlank();
        }

        public boolean isParsed() {
            return Boolean.TRUE.equals(parsed);
        }

        public boolean hasAssignedType() {
            return assignedDocType != null && !assignedDocType.isBlank();
        }

        public boolean hasEngineSegments() {
            return !engineSegments.isEmpty();
        }
    }

    /** One engine-found boundary inside a bundle: 1-based inclusive pages plus, when the engine
     *  decided it, the segment's own type and the score behind it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EngineSegment(Integer pageStart, Integer pageEnd, String assignedDocType,
                                Double engineConfidence) {}

    /** A human-supplied correction or manually-keyed figure (e.g. from an unreadable doc).
     *  Authoritative over the model's own reading. */
    public record AnalystNote(String docRef, String text, String source) {}
    /**
     * The loan facts the prompt and the deterministic calculators read.
     *
     * <p>{@code program} and {@code loanPurpose} exist because there is no single large-deposit
     * test to apply: Fannie Mae measures a deposit against 50% of qualifying monthly income and
     * only on a purchase, FHA against 1% of the Adjusted Value on any transaction. Without both
     * facts the engine cannot pick a rule, and it reports the screen as not run rather than
     * assuming one — see {@code AssetsCalcService}.
     *
     * <p>Both are optional strings rather than enums so an unrecognized value from the suite
     * degrades to "not supplied" instead of failing the whole request; {@code LoanBasis} does the
     * parsing and returns null for anything it does not know.
     *
     * <p>{@code salesPrice}, {@code downPayment}, {@code propertyAddress}, {@code consummationDate}
     * and {@code sellerCredits} are the application terms the submission analyzer compares the
     * purchase contract against. All nullable: a null renders as "not on application" so the model
     * reports that comparison as NOT_COMPARABLE instead of guessing. {@code consummationDate} is an
     * ISO date string; {@code sellerCredits} is the suite's sum of seller concessions across the
     * fees ledger, null when the ledger is empty.
     *
     * @param urlaIncome  the URLA/1003 stated income the suite itemizes per borrower/source
     *                    ({@code {"statedTotalMonthly", "borrowers":[{"name","sources":
     *                    [{"type","employer","monthlyStated"}]}]}}) — kept as raw JSON so the
     *                    suite can evolve the shape without an engine lockstep; rendered
     *                    verbatim into the prompt for the income analyzer's reconciliation
     *                    step. Null when the suite sent none.
     * @param program     agency ruleset name: FANNIE_MAE, FREDDIE_MAC, FHA, VA, or USDA
     * @param loanPurpose PURCHASE or REFINANCE
     */
    public record LoanSnapshot(Double loanAmount, Double propertyValue,
                               List<String> borrowers, Double monthlyIncome,
                               JsonNode urlaIncome, String program, String loanPurpose,
                               Double salesPrice, Double downPayment, String propertyAddress,
                               String consummationDate, Double sellerCredits) {

        /**
         * The canonical constructor is the JSON creator, for the same reason {@link DocMeta}
         * marks its own: a record with a second, shorter constructor is otherwise ambiguous to
         * Jackson, and binding through the convenience form would silently drop program and
         * purpose from every request that sent them.
         */
        @JsonCreator
        public LoanSnapshot {
        }

        /** The pre-program signature every existing caller still satisfies. */
        public LoanSnapshot(Double loanAmount, Double propertyValue, List<String> borrowers,
                            Double monthlyIncome, JsonNode urlaIncome) {
            this(loanAmount, propertyValue, borrowers, monthlyIncome, urlaIncome, null, null);
        }

        /** The pre-application-terms signature (program + purpose, no contract comparison facts). */
        public LoanSnapshot(Double loanAmount, Double propertyValue, List<String> borrowers,
                            Double monthlyIncome, JsonNode urlaIncome, String program,
                            String loanPurpose) {
            this(loanAmount, propertyValue, borrowers, monthlyIncome, urlaIncome, program, loanPurpose,
                    null, null, null, null, null);
        }

        /** True when the caller sent at least one application term for contract comparison. */
        public boolean hasApplicationTerms() {
            return salesPrice != null || downPayment != null || propertyAddress != null
                    || consummationDate != null || sellerCredits != null;
        }
    }

    /** Classifier-only: the org's folders + doc types the model must choose from. */
    public record OrgCatalog(List<Folder> folders, List<DocType> docTypes) {
        public record Folder(String templateId, String name) {}
        public record DocType(String id, String name) {}
    }
}
