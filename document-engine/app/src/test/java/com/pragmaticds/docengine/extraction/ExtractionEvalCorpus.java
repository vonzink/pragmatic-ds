package com.pragmaticds.docengine.extraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads the evaluation corpus for the DETERMINISTIC extraction path — rule packs and extraction
 * schemas, not the gated AI dialects.
 *
 * <p>This is the measuring stick the AI path already had ({@code BankStatementEvalCorpus}) and the
 * deterministic path did not. Before it, every document type's accuracy lived in a bespoke
 * hand-written IT — {@code W2ExtractionIT} 243 lines, {@code TaxReturnExtractionIT} 532,
 * {@code ScheduleEExtractionIT} 662 — which prove a type works but publish NO number, so nothing
 * could answer "did adding type N make type N-1 worse". Those ITs stay: they assert mechanism
 * (which rung fired, which evidence box) far more deeply than a scorer should. This corpus scores
 * OUTCOME across every type at once, so a new type costs a small JSON file rather than a 300-line
 * test class.
 *
 * <p><b>Ground truth is not re-authored here.</b> A case file names a fixture and the document
 * types it should split into; the field expectations come from that fixture's {@code
 * fixtures/truth/<name>.json} {@code expectedFields}, which {@code generate.py} emits BY
 * CONSTRUCTION from the draw calls and CI sha256-pins. Copying those values into a second file
 * would create a pair that can silently disagree, and the copy would be the one nobody regenerates.
 *
 * <p><b>Fixtures, never corpus — in the committed tree.</b> A committed case names a fixture NAME,
 * and the format has no field for document bytes at all, so a real borrower document has no route
 * into the build by construction, not by reviewer vigilance.
 *
 * <p><b>The same format, over real documents, by hand.</b> {@link #loadDirectory} reads the
 * identical case shape from a directory tree — {@code <TYPE>/<case>/case.json} beside a {@code
 * truth.json} carrying the worker's words for that document and the hand-labelled {@code
 * expectedFields}. That tree lives in the gitignored {@code corpus/eval/} and is read only by the
 * manual {@code corpusEval} Gradle task, never by {@code test}. One loader, one scorer, one report:
 * the synthetic number and the real number are the same number computed on different input,
 * which is the only way a 99% on fixtures can be honestly compared with a 30% on real forms.
 */
final class ExtractionEvalCorpus {

    private static final String ROOT = "/extraction/eval/";
    static final String CASE_FILE = "case.json";
    static final String TRUTH_FILE = "truth.json";
    static final String BASELINE_FILE = "baseline.json";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final List<EvalCase> cases;

    private ExtractionEvalCorpus(List<EvalCase> cases) {
        this.cases = List.copyOf(cases);
    }

    /** The committed, synthetic-only corpus under {@code src/test/resources/extraction/eval}. */
    static ExtractionEvalCorpus load() {
        Index index = readJson(ROOT + "index.json", Index.class);
        return new ExtractionEvalCorpus(index.cases().stream().map(ExtractionEvalCorpus::loadCase).toList());
    }

    /** A corpus assembled in code — how the scorer and gate are unit-tested without resources. */
    static ExtractionEvalCorpus of(List<EvalCase> cases) {
        return new ExtractionEvalCorpus(cases);
    }

    /**
     * One case from in-memory JSON — a {@code case.json} node and its {@code truth.json} node —
     * exactly as {@link #loadDirectory} would bind them. How the gold exporter's output is
     * round-tripped through this loader in tests.
     */
    static EvalCase parseCase(JsonNode caseNode, JsonNode truth) {
        try {
            return MAPPER.treeToValue(caseNode, CaseSpec.class).resolve(truth);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("unparseable case.json", failure);
        }
    }

    /**
     * Cases from a directory tree: {@code <root>/<TYPE>/<case>/case.json} + {@code truth.json}.
     * Types and cases are discovered from the directory names alone — nothing here knows which
     * document types exist. A missing root loads an empty corpus; a case filed under a type
     * directory that its documents do not match is rejected, because a mislabelled directory
     * would silently score one type's documents under another's floors.
     */
    static ExtractionEvalCorpus loadDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            return new ExtractionEvalCorpus(List.of());
        }
        List<EvalCase> cases = new ArrayList<>();
        for (Path typeDir : children(root)) {
            String type = typeDir.getFileName().toString();
            for (Path caseDir : children(typeDir)) {
                Path caseFile = caseDir.resolve(CASE_FILE);
                if (!Files.isRegularFile(caseFile)) {
                    continue;
                }
                CaseSpec spec = readJson(caseFile, CaseSpec.class);
                String id = caseDir.getFileName().toString();
                if (!id.equals(spec.id())) {
                    throw new IllegalStateException(
                            "eval case id does not match directory: " + caseDir + " declares " + spec.id());
                }
                for (DocumentSpec document : spec.documents()) {
                    if (!type.equals(document.type())) {
                        throw new IllegalStateException(
                                "eval case %s is filed under %s but declares a %s document"
                                        .formatted(id, type, document.type()));
                    }
                }
                Path truthFile = caseDir.resolve(TRUTH_FILE);
                if (!Files.isRegularFile(truthFile)) {
                    throw new IllegalStateException("eval case " + caseDir + " has no " + TRUTH_FILE);
                }
                cases.add(spec.resolve(readTree(truthFile)));
            }
        }
        return new ExtractionEvalCorpus(cases);
    }

    private static List<Path> children(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    List<EvalCase> cases() {
        return cases;
    }

    EvalCase caseById(String id) {
        return cases.stream()
                .filter(testCase -> testCase.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown eval case: " + id));
    }

    /** Every document type the corpus covers, in first-seen order. */
    List<String> coveredTypes() {
        return cases.stream()
                .flatMap(testCase -> testCase.documents().stream())
                .map(ExpectedDocument::type)
                .distinct()
                .toList();
    }

    static Baseline baseline() {
        return readJson(ROOT + BASELINE_FILE, Baseline.class);
    }

    /** A baseline file on disk, or an empty (gating nothing) one when absent. */
    static Baseline baselineAt(Path file) {
        if (!Files.isRegularFile(file)) {
            return new Baseline(Map.of(), Map.of());
        }
        return readJson(file, Baseline.class);
    }

    private static EvalCase loadCase(String id) {
        CaseSpec spec = readJson(ROOT + id + ".json", CaseSpec.class);
        if (!id.equals(spec.id())) {
            throw new IllegalStateException("eval case id does not match file name: " + id);
        }
        return spec.resolve(truth(spec.fixture()));
    }

    // ── fixture truth bridge ────────────────────────────────────────────────
    // Mirrors AbstractClassificationIT's walk-up so the corpus stays loadable (and therefore
    // unit-testable) without a Spring context or a Docker daemon.

    private static Path fixtureTruthDir() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++, current = current.getParent()) {
            Path truth = current.resolve("fixtures").resolve("truth");
            if (Files.isDirectory(truth)) {
                return truth;
            }
        }
        throw new IllegalStateException(
                "fixtures/truth not found above " + System.getProperty("user.dir"));
    }

    static JsonNode truth(String fixtureName) {
        return readTree(fixtureTruthDir().resolve(fixtureName + ".json"));
    }

    private static JsonNode readTree(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static <T> T readJson(String resource, Class<T> type) {
        try (InputStream stream = ExtractionEvalCorpus.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing eval resource: " + resource);
            }
            return MAPPER.readValue(stream, type);
        } catch (IOException failure) {
            throw new IllegalStateException("invalid eval resource: " + resource, failure);
        }
    }

    private static <T> T readJson(Path file, Class<T> type) {
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException failure) {
            throw new IllegalStateException("invalid eval file: " + file, failure);
        }
    }

    private record Index(List<String> cases) {
        private Index {
            cases = List.copyOf(cases);
        }
    }

    /**
     * How the fixture's layout tree is seeded before EXTRACTING runs. The grid rungs
     * ({@code LABEL_BELOW} via cells, {@code ROW_CELL}) read the TABLE → ROW → CELL tree the worker
     * emits; a fixture whose truth words carry no {@code "cell"} marker needs none, and seeding one
     * is a documented no-op rather than an error.
     */
    enum Layout {
        /** No layout tree — flat text-span extraction only. */
        NONE,
        /** Unruled grid: the worker's 0.75 confidence across the tree. */
        GRID,
        /** Ruled grid: vector rulings confirmed the whitespace grid, 0.95. */
        GRID_RULED
    }

    /**
     * The committed half of a case — everything ground truth cannot supply.
     *
     * @param fixture the {@code fixtures/truth} name for a committed case; a directory case has
     *     its truth beside it and may omit this, in which case the id stands in
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CaseSpec(
            String id,
            String fixture,
            Layout layout,
            boolean synthetic,
            String note,
            List<String> coverage,
            List<DocumentSpec> documents) {

        CaseSpec {
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            documents = documents == null ? List.of() : List.copyOf(documents);
            layout = layout == null ? Layout.NONE : layout;
            fixture = fixture == null || fixture.isBlank() ? id : fixture;
            if (documents.isEmpty()) {
                throw new IllegalStateException("eval case " + id + " declares no documents");
            }
        }

        /** Binds each truth {@code expectedFields} entry to the document owning its page. */
        EvalCase resolve(JsonNode truth) {
            JsonNode expectedFields = truth.get("expectedFields");
            List<ExpectedDocument> resolved = new ArrayList<>();
            for (DocumentSpec document : documents) {
                Map<String, ExpectedField> fields = new LinkedHashMap<>();
                if (expectedFields != null) {
                    for (JsonNode entry : expectedFields) {
                        int pageIndex = entry.path("pageIndex").asInt(0);
                        if (!document.owns(pageIndex)) {
                            continue;
                        }
                        ExpectedField field = ExpectedField.from(entry);
                        if (fields.put(field.key(), field) != null) {
                            throw new IllegalStateException(
                                    "duplicate expected field " + field.key() + " in " + fixture);
                        }
                    }
                }
                resolved.add(new ExpectedDocument(document.type(), fields));
            }
            return new EvalCase(id, fixture, layout, synthetic, note, coverage, resolved, truth);
        }
    }

    /**
     * One logical document the fixture should split into, in ordinal order.
     *
     * @param pages the package page indexes this document owns; empty means every page, which is
     *     the single-document case every fixture but the combined packages is
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentSpec(String type, List<Integer> pages) {
        DocumentSpec {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        boolean owns(int pageIndex) {
            return pages.isEmpty() || pages.contains(pageIndex);
        }
    }

    /**
     * @param truth the fixture-truth document the case runs on: {@code pages[].words[]} become the
     *     package's text spans, {@code expectedFields} became {@code documents}
     */
    record EvalCase(
            String id,
            String fixture,
            Layout layout,
            boolean synthetic,
            String note,
            List<String> coverage,
            List<ExpectedDocument> documents,
            JsonNode truth) {

        EvalCase {
            coverage = List.copyOf(coverage);
            documents = List.copyOf(documents);
        }

        boolean ruled() {
            return layout == Layout.GRID_RULED;
        }

        boolean needsLayout() {
            return layout != Layout.NONE;
        }

        /** The page/word tree the IT seeds as text spans. */
        JsonNode truthPages() {
            JsonNode pages = truth == null ? null : truth.get("pages");
            if (pages == null || !pages.isArray()) {
                throw new IllegalStateException("eval case " + id + " has no truth pages");
            }
            return pages;
        }
    }

    record ExpectedDocument(String type, Map<String, ExpectedField> fields) {
        ExpectedDocument {
            fields = new LinkedHashMap<>(fields);
        }
    }

    /**
     * What one field should produce, lifted verbatim from the fixture's truth. Each assertion is
     * scored independently so a normalizer regression reads as a normalizer regression rather than
     * collapsing into one undifferentiated "accuracy" number.
     *
     * @param groupKey the repeating-group occupancy key, empty for an ungrouped field — part of the
     *     identity, because a grouped field has one row PER OCCURRENCE and keying by name alone
     *     silently keeps whichever sorted last (the trap {@code currentFieldsByName} throws on)
     */
    record ExpectedField(
            String name,
            String groupKey,
            String displayedText,
            String method,
            String normalizedText,
            String normalizedNumber,
            String normalizedDate,
            int pageIndex) {

        /** An ungrouped field the document prints with exactly this text; nothing else pinned. */
        static ExpectedField present(String name, String displayedText) {
            return new ExpectedField(name, "", displayedText, null, null, null, null, 0);
        }

        /** An ungrouped field the document deliberately does not print — capturing it is the failure. */
        static ExpectedField absent(String name) {
            return new ExpectedField(name, "", null, "NONE", null, null, null, 0);
        }

        static ExpectedField from(JsonNode entry) {
            JsonNode normalized = entry.path("normalized");
            return new ExpectedField(
                    entry.get("field").asText(),
                    entry.path("groupKey").asText(""),
                    entry.path("displayedText").asText(null),
                    entry.path("method").asText(null),
                    text(normalized, "text"),
                    text(normalized, "number"),
                    text(normalized, "date"),
                    entry.path("pageIndex").asInt(0));
        }

        private static String text(JsonNode normalized, String field) {
            JsonNode value = normalized.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        }

        /** {@code fieldName#groupKey} — the coordinate a grouped field actually has. */
        String key() {
            return name + "#" + groupKey;
        }

        /**
         * Truth pins a value, so the engine must capture one. A null {@code displayedText} paired
         * with method {@code NONE} is how {@code generate.py} records a field the fixture
         * deliberately does NOT draw ({@code paystub_missing_field}'s {@code payDate}) — there the
         * expectation inverts, and capturing anything is the failure.
         */
        boolean mustCapture() {
            return displayedText != null;
        }

        boolean assertsValue() {
            return displayedText != null;
        }
    }

    /**
     * The committed regression bar. Metrics are FLOORS: a run may exceed them, and falling below
     * any one fails the build. A new type therefore lands with its own honest floor — a type that
     * extracts 6 of 10 fields does not break CI, it just cannot quietly drop to 5.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Baseline(Map<String, Double> overall, Map<String, Map<String, Double>> byType) {
        Baseline {
            overall = overall == null ? Map.of() : new LinkedHashMap<>(overall);
            byType = byType == null ? Map.of() : new LinkedHashMap<>(byType);
        }

        boolean isEmpty() {
            return overall.isEmpty() && byType.isEmpty();
        }
    }
}
