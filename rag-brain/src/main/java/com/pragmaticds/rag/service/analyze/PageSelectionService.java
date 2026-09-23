package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.pack.PageSelectionProfile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trims a multi-page PDF down to the pages a profile says are worth sending to the vision
 * model, so state returns, worksheets and instructions never become vision tokens.
 *
 * CONSERVATIVE BY CONSTRUCTION: a page is dropped ONLY when it has extractable text, that
 * text matches no keep pattern, and it is not a continuation of a kept page. Anything the
 * service cannot classify — an image-only (scanned) page, an unparseable file, a profile
 * that matches nothing — is kept, which means a fully scanned PDF is always sent whole.
 * It never returns zero pages. Bytes stay in memory.
 */
@Service
public class PageSelectionService {

    private static final Logger log = LoggerFactory.getLogger(PageSelectionService.class);

    /**
     * @param bytes        what to send: the trimmed PDF when {@code filtered}, else the original
     * @param pagesTotal   pages in the original document; -1 when it could not be parsed
     * @param pagesKept    pages in {@code bytes}; -1 when unknown (parse failure)
     * @param matchedForms distinct keep-rule names that identified a kept page (first match per page)
     * @param filtered     true when pages were actually removed
     * @param note         why filtering did not apply; null when it did
     */
    public record SelectionResult(byte[] bytes, int pagesTotal, int pagesKept,
                                  List<String> matchedForms, boolean filtered, String note) {}

    public SelectionResult select(byte[] pdfBytes, PageSelectionProfile profile) {
        int total = -1;
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            total = doc.getNumberOfPages();
            if (total <= profile.minPages()) {
                return whole(pdfBytes, total, "at or under min-pages (" + profile.minPages() + ")");
            }

            String[] pageText = new String[total];
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 0; i < total; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                pageText[i] = normalize(stripper.getText(doc));
            }

            Set<Integer> keep = new LinkedHashSet<>();
            Set<String> forms = new LinkedHashSet<>();
            for (int i = 0; i < total; i++) {
                if (pageText[i].isBlank()) {
                    keep.add(i);          // no text layer: unclassifiable, so never droppable
                    continue;
                }
                List<String> matched = matchForms(pageText[i], profile);
                if (!matched.isEmpty()) {
                    keep.add(i);
                    forms.addAll(matched);
                }
            }

            if (keep.isEmpty()) {
                return whole(pdfBytes, total, "no page matched the profile");
            }

            // Continuation: later pages of a multi-page form often carry no header. Extend from
            // each anchor until the window runs out or a drop-hint marks a clear boundary.
            for (int anchor : new ArrayList<>(keep)) {
                for (int offset = 1; offset <= profile.continuationWindow(); offset++) {
                    int page = anchor + offset;
                    if (page >= total) {
                        break;
                    }
                    if (keep.contains(page)) {
                        continue;
                    }
                    if (isDropHint(pageText[page], profile)) {
                        break;
                    }
                    keep.add(page);
                }
            }

            if (keep.size() >= total) {
                return whole(pdfBytes, total, "every page is needed");
            }

            int pagesKept = keep.size();
            byte[] trimmed = trim(doc, keep, total);
            return new SelectionResult(trimmed, total, pagesKept, List.copyOf(forms), true, null);
        } catch (Exception e) {
            // Failing open is the safe direction: send everything rather than risk dropping a page.
            log.warn("Page selection failed; sending the document whole: {}", e.toString());
            return whole(pdfBytes, total, "page selection failed: " + e.toString());
        }
    }

    private static SelectionResult whole(byte[] pdfBytes, int total, String note) {
        return new SelectionResult(pdfBytes, total, total, List.of(), false, note);
    }

    /**
     * Lowercase, fold the typographic quotes/dashes IRS forms actually print (a form prints
     * "Partner’s Share", not an ASCII apostrophe — an unfolded pattern would silently never
     * match), and collapse whitespace so a header split across lines still matches.
     */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.US)
                .replace('‘', '\'').replace('’', '\'')
                .replace('–', '-').replace('—', '-').replace('‑', '-')
                .replaceAll("[\\s\\u00a0]+", " ");
    }

    /**
     * Every keep rule whose pattern appears on the page. All matches are collected, not just
     * the first: 1040-family schedules carry "(Form 1040)" in their masthead, so first-match
     * would label a Schedule C page as "1040" in the report the analyst reads.
     */
    private static List<String> matchForms(String normalizedPageText, PageSelectionProfile profile) {
        List<String> matched = new ArrayList<>();
        for (PageSelectionProfile.KeepRule rule : profile.keep()) {
            for (String pattern : rule.patterns()) {
                if (pattern != null && !pattern.isBlank()
                        && normalizedPageText.contains(normalize(pattern))) {
                    matched.add(rule.name());
                    break;   // one hit per rule is enough
                }
            }
        }
        return matched;
    }

    private static boolean isDropHint(String normalizedPageText, PageSelectionProfile profile) {
        for (String hint : profile.dropHints()) {
            if (hint != null && !hint.isBlank()
                    && normalizedPageText.contains(normalize(hint))) {
                return true;
            }
        }
        return false;
    }

    /** Drops every page not in {@code keep} from the open document and serializes it. */
    private static byte[] trim(PDDocument doc, Set<Integer> keep, int total) throws IOException {
        for (int i = total - 1; i >= 0; i--) {
            if (!keep.contains(i)) {
                doc.removePage(i);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos);
        return bos.toByteArray();
    }
}
