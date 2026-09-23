package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.pack.PageSelectionProfile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns raw analyze documents into what the model reads: native content blocks for files it can
 * SEE (PDF, images) and inline text for files it can only READ (the document engine's parsed
 * markdown, JSON, MISMO XML, plain text), applying the per-doc ladder (native block → inline
 * text → skip-with-reason) and the v1 caps (≤ maxDocs, ≤ maxPages of attached pages, ≤ maxBytes
 * of attached payload, ≤ maxTextBytes of inline text). Over any cap, the OLDEST documents are
 * skipped first (newest statements/paystubs matter most), and every skipped doc is reported.
 * Bytes never leave memory.
 *
 * <p>Blocks are named by the suite's document id, never the file name. The name rides along on
 * the attachment, and a file name is a label a person typed — wrong often enough to matter —
 * that the model must not treat as evidence. The id is what findings cite, so it is the only
 * name the model needs.
 *
 * <p>Text documents are not media: Spring AI's Anthropic path carries images and PDFs as
 * attachments and nothing else, so text is rendered into the prompt by {@code AnalysisService}
 * between BEGIN/END markers. Before this ladder had a text rung, every parsed document the
 * suite sent as markdown was skipped as "unsupported" and the model was told it was unreadable.
 *
 * <p>When a {@link PageSelectionProfile} is active, each PDF is trimmed to its kept pages
 * BEFORE the caps are evaluated, so filtering also frees cap headroom for other docs.
 */
@Service
public class DocumentBlockService {

    private static final Logger log = LoggerFactory.getLogger(DocumentBlockService.class);

    /** Extensions read as text when the declared type is missing or generic. */
    private static final List<String> TEXT_EXTENSIONS =
            List.of(".md", ".markdown", ".txt", ".json", ".xml");

    /**
     * The fence markers {@code AnalysisService} wraps document text in — its own, and the engine
     * rendering that accompanies it. Matched here so a document
     * whose own text contains one cannot close its fence early and have the rest of itself read
     * as prompt. Case- and space-insensitive: the defence must not depend on the exact spelling
     * a document happens to use.
     */
    private static final Pattern FENCE_MARKER = Pattern.compile(
            "<<<\\s*(?:BEGIN|END)\\s+(?:DOCUMENT|ENGINE\\s+RENDERING)", Pattern.CASE_INSENSITIVE);

    private final PageSelectionService pageSelection;
    private final int maxDocs;
    private final int maxPages;
    private final long maxBytes;
    private final long maxTextBytes;

    public DocumentBlockService(
            PageSelectionService pageSelection,
            @Value("${ragbrain.rag.analyze.max-docs:20}") int maxDocs,
            @Value("${ragbrain.rag.analyze.max-pages:100}") int maxPages,
            @Value("${ragbrain.rag.analyze.max-bytes:26214400}") long maxBytes,
            @Value("${ragbrain.rag.analyze.max-text-bytes:400000}") long maxTextBytes) {
        this.pageSelection = pageSelection;
        this.maxDocs = maxDocs;
        this.maxPages = maxPages;
        this.maxBytes = maxBytes;
        this.maxTextBytes = maxTextBytes;
    }

    /** A native block plus its estimated page count, carrying the suite document id it was built for. */
    public record DocBlock(String id, Media media, int pages) {
        /** A block with no id — for callers that never render a document list. */
        public DocBlock(Media media, int pages) {
            this(null, media, pages);
        }
    }

    /** A document the model reads as text in the prompt rather than sees as an attachment. */
    public record TextDoc(String id, String contentType, String text) {}

    /**
     * Result of building blocks for a run: the attached blocks, their page total, skips,
     * page-trim reports, and the documents rendered inline as text.
     */
    public record BuildResult(List<DocBlock> blocks, int pageCount, List<SkippedDoc> skipped,
                              List<FilteredDoc> filtered, List<TextDoc> texts) {
        public BuildResult {
            texts = texts == null ? List.of() : texts;
        }

        public BuildResult(List<DocBlock> blocks, int pageCount, List<SkippedDoc> skipped) {
            this(blocks, pageCount, skipped, List.of(), List.of());
        }

        public BuildResult(List<DocBlock> blocks, int pageCount, List<SkippedDoc> skipped,
                           List<FilteredDoc> filtered) {
            this(blocks, pageCount, skipped, filtered, List.of());
        }
    }

    /**
     * @param profile page-selection profile for this run; null ⇒ send every page of every PDF.
     *                Page selection runs BEFORE the caps, so trimming also frees cap headroom.
     *                Caps are evaluated after MIME inference and page selection, so a doc that
     *                is also unsupported/unreadable reports that category rather than a cap
     *                category.
     */
    public BuildResult build(List<DocInput> docs, PageSelectionProfile profile) {
        List<DocBlock> blocks = new ArrayList<>();
        List<TextDoc> texts = new ArrayList<>();
        List<SkippedDoc> skipped = new ArrayList<>();
        List<FilteredDoc> filtered = new ArrayList<>();

        // Newest first so cap trimming drops the OLDEST. createdAt ascending = oldest first;
        // reverse to process newest first and let the tail (oldest) fall off the caps.
        List<DocInput> ordered = docs.stream()
                .sorted(Comparator.comparingLong(DocInput::createdAt).reversed())
                .toList();

        int usedDocs = 0;
        int usedPages = 0;
        long usedBytes = 0;
        long usedTextBytes = 0;

        for (DocInput doc : ordered) {
            if (usedDocs >= maxDocs) {
                skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.OVER_DOC_CAP,
                        "over document cap (" + maxDocs + " max)"));
                continue;
            }

            MimeType mime = inferMime(doc);
            if (mime == null) {
                if (!isTextDocument(doc.contentType(), doc.fileName())) {
                    skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.UNSUPPORTED_TYPE,
                            "unsupported document type: " + String.valueOf(doc.contentType())));
                    continue;
                }
                byte[] raw = doc.bytes() == null ? new byte[0] : doc.bytes();
                if (usedTextBytes + raw.length > maxTextBytes) {
                    skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.OVER_SIZE_CAP,
                            "over inline text cap (" + maxTextBytes + " bytes max)"));
                    continue;
                }
                texts.add(new TextDoc(doc.id(), textContentType(doc), decodeText(raw)));
                usedDocs++;
                usedTextBytes += raw.length;
                continue;
            }

            byte[] bytes = doc.bytes();
            int pages;
            FilteredDoc report = null;
            if (mime.equals(Media.Format.DOC_PDF)) {
                PageSelectionService.SelectionResult sel =
                        profile == null ? null : pageSelection.select(bytes, profile);
                int total = sel != null ? sel.pagesTotal() : pdfPageCount(doc);
                if (total < 0) {
                    log.warn("Could not read PDF doc {}; skipping", doc.id());
                    skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.UNREADABLE_PDF,
                            "unreadable PDF (no native block, no text)"));
                    continue;
                }
                if (sel != null && sel.filtered()) {
                    bytes = sel.bytes();
                    pages = sel.pagesKept();
                    report = new FilteredDoc(doc.id(), doc.fileName(), total,
                            sel.pagesKept(), sel.matchedForms());
                } else {
                    pages = total;
                }
            } else {
                pages = 1;
            }

            if (usedBytes + bytes.length > maxBytes) {
                skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.OVER_SIZE_CAP,
                        "over payload size cap (" + maxBytes + " bytes max)"));
                continue;
            }
            if (usedPages + pages > maxPages) {
                skipped.add(new SkippedDoc(doc.id(), doc.fileName(), SkipCategory.OVER_PAGE_CAP,
                        "over page cap (" + maxPages + " total pages max)"));
                continue;
            }

            Media media = Media.builder()
                    .mimeType(mime)
                    .data(new ByteArrayResource(bytes))
                    .name(blockName(doc))
                    .build();
            blocks.add(new DocBlock(doc.id(), media, pages));
            if (report != null) {
                filtered.add(report);   // only report docs that actually reached the model
            }
            usedDocs++;
            usedPages += pages;
            usedBytes += bytes.length;
        }

        // Report skipped oldest-first (stable, human-readable ordering).
        // Null-tolerant: a context DocMeta may carry a fileName with no id, and sorting the
        // SUCCESS path must never turn a working analyze into a 500.
        skipped.sort(Comparator.comparing(SkippedDoc::id, Comparator.nullsLast(Comparator.naturalOrder())));
        filtered.sort(Comparator.comparing(FilteredDoc::id, Comparator.nullsLast(Comparator.naturalOrder())));
        return new BuildResult(List.copyOf(blocks), usedPages, List.copyOf(skipped),
                List.copyOf(filtered), List.copyOf(texts));
    }

    /**
     * Builds a native block for ONE document — the single-doc counterpart to
     * {@link #build(List, PageSelectionProfile)} for callers that process one
     * document at a time (e.g. {@code ExtractionService}). Applies only the
     * native rung of the ladder (PDF/image block → unsupported/unreadable); no
     * inline text, no multi-doc caps, no page-selection trimming, no newest-first ordering.
     *
     * @return the block, or null when the doc is unsupported or unreadable.
     */
    public DocBlock buildOne(DocInput doc) {
        MimeType mime = inferMime(doc);
        if (mime == null) {
            return null;
        }
        int pages = 1;
        if (mime.equals(Media.Format.DOC_PDF)) {
            pages = pdfPageCount(doc);
            if (pages < 0) {
                return null;
            }
        }
        Media media = Media.builder()
                .mimeType(mime)
                .data(new ByteArrayResource(doc.bytes()))
                .name(blockName(doc))
                .build();
        return new DocBlock(doc.id(), media, pages);
    }

    /**
     * Text the model can read inline: any {@code text/*} type, JSON, XML (MISMO), by declared
     * content type or, when the type is missing or generic, by extension.
     */
    static boolean isTextDocument(String contentType, String fileName) {
        String ct = baseType(contentType);
        if (ct.startsWith("text/")) {
            return true;
        }
        if (ct.equals("application/json") || ct.endsWith("+json")) {
            return true;
        }
        if (ct.equals("application/xml") || ct.endsWith("+xml")) {
            return true;
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** The block's name as the provider sees it: the document id, never the file name. */
    private static String blockName(DocInput doc) {
        return doc.id() == null ? "document" : doc.id();
    }

    /** The declared type without parameters, lower-cased; empty when none was declared. */
    private static String baseType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String ct = contentType.toLowerCase(Locale.US).strip();
        int semi = ct.indexOf(';');
        return semi < 0 ? ct : ct.substring(0, semi).strip();
    }

    /** The type announced to the model for an inline text doc: declared, else by extension. */
    private static String textContentType(DocInput doc) {
        String ct = baseType(doc.contentType());
        if (!ct.isEmpty() && !ct.equals("application/octet-stream")) {
            return ct;
        }
        String name = doc.fileName() == null ? "" : doc.fileName().toLowerCase(Locale.US);
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".xml")) {
            return "application/xml";
        }
        return "text/plain";
    }

    /** UTF-8, with a leading byte-order mark dropped so the first line is the first line. */
    private static String decodeText(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return defangFenceMarkers(text);
    }

    /**
     * Breaks any fence marker the document's own text contains, so document content can never
     * end its own fence and be read as instruction. Document text is NOT trusted input: the
     * engine's parsed rendering carries field values read off the page, and a page is whatever
     * someone put in front of the scanner.
     *
     * <p>Breaks the marker rather than deleting it — a reader of the prompt still sees what the
     * document said — and touches nothing else, so an ordinary document is byte-identical.
     */
    static String defangFenceMarkers(String text) {
        return FENCE_MARKER.matcher(text).replaceAll(m -> "<< " + m.group().substring(2));
    }

    /**
     * Maps a doc to a native Media MIME, by declared content-type then filename
     * extension. Returns null when the type is not vision-supported.
     */
    private MimeType inferMime(DocInput doc) {
        String ct = doc.contentType() == null ? "" : doc.contentType().toLowerCase(Locale.US);
        String name = doc.fileName() == null ? "" : doc.fileName().toLowerCase(Locale.US);
        if (ct.contains("pdf") || name.endsWith(".pdf")) {
            return Media.Format.DOC_PDF;
        }
        if (ct.contains("png") || name.endsWith(".png")) {
            return Media.Format.IMAGE_PNG;
        }
        if (ct.contains("jpeg") || ct.contains("jpg") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return Media.Format.IMAGE_JPEG;
        }
        if (ct.contains("gif") || name.endsWith(".gif")) {
            return Media.Format.IMAGE_GIF;
        }
        if (ct.contains("webp") || name.endsWith(".webp")) {
            return Media.Format.IMAGE_WEBP;
        }
        return null;
    }

    /** PDF page count via PDFBox; -1 when the file cannot be parsed at all. */
    private int pdfPageCount(DocInput doc) {
        try (PDDocument pdf = Loader.loadPDF(doc.bytes())) {
            return pdf.getNumberOfPages();
        } catch (Exception e) {
            log.warn("PDFBox could not read doc {}: {}", doc.id(), e.getMessage());
            return -1;
        }
    }
}
