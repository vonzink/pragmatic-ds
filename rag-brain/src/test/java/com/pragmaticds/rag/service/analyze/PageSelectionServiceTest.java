package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.pack.PageSelectionProfile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageSelectionServiceTest {

    private final PageSelectionService service = new PageSelectionService();

    /** A real PDF; each argument is one page's text. null = an image-only page (no text layer). */
    private static byte[] pdf(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                if (text != null) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static String textOf(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pageCountOf(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }

    private static PageSelectionProfile profile(int minPages, int continuationWindow) {
        return new PageSelectionProfile("test", minPages, continuationWindow,
                List.of(new PageSelectionProfile.KeepRule("1040", List.of("Form 1040")),
                        new PageSelectionProfile.KeepRule("Schedule E", List.of("SCHEDULE E"))),
                List.of("State of", "Worksheet"));
    }

    @Test
    void keepsFederalPagesAndDropsStateAndWorksheetPages() throws IOException {
        byte[] bytes = pdf(
                "Form 1040 Individual Income Tax Return",
                "State of Colorado Individual Return",
                "SCHEDULE E Supplemental Income and Loss",
                "Worksheet keep for your records");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertTrue(r.filtered());
        assertEquals(4, r.pagesTotal());
        assertEquals(2, r.pagesKept());
        assertEquals(List.of("1040", "Schedule E"), r.matchedForms());
        assertEquals(2, pageCountOf(r.bytes()), "trimmed PDF carries only the kept pages");
        String kept = textOf(r.bytes());
        assertTrue(kept.contains("Form 1040"));
        assertTrue(kept.contains("SCHEDULE E"), "kept pages stay in original order");
        assertFalse(kept.contains("State of Colorado"));
        assertFalse(kept.contains("keep for your records"));
    }

    @Test
    void keepsHeaderlessContinuationPage() throws IOException {
        byte[] bytes = pdf(
                "SCHEDULE E Supplemental Income and Loss",
                "rental property totals continued 12345",
                "State of Colorado Individual Return");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 1));

        assertTrue(r.filtered());
        assertEquals(2, r.pagesKept(), "the headerless page after Schedule E is a continuation");
        String kept = textOf(r.bytes());
        assertTrue(kept.contains("continued 12345"));
        assertFalse(kept.contains("State of Colorado"));
    }

    @Test
    void dropHintStopsContinuationKeeping() throws IOException {
        byte[] bytes = pdf(
                "Form 1040 Individual Income Tax Return",
                "State of Colorado Individual Return",
                "plain text with no form header");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 2));

        assertEquals(1, r.pagesKept(), "a drop-hint page ends the continuation run");
        assertFalse(textOf(r.bytes()).contains("no form header"));
    }

    @Test
    void pagesWithNoTextLayerAreNeverDropped() throws IOException {
        byte[] bytes = pdf(null, null, "State of Colorado Individual Return");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertTrue(r.filtered());
        assertEquals(2, r.pagesKept(), "unclassifiable (scanned) pages are kept");
        assertEquals(2, pageCountOf(r.bytes()));
    }

    @Test
    void fullyScannedPdfIsSentWhole() throws IOException {
        byte[] bytes = pdf(null, null, null);

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertFalse(r.filtered(), "nothing is droppable, so send the original bytes");
        assertSame(bytes, r.bytes());
        assertEquals(3, r.pagesKept());
    }

    @Test
    void noMatchingPageSendsWhole() throws IOException {
        byte[] bytes = pdf("State of Colorado Individual Return", "Worksheet keep for your records");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertFalse(r.filtered(), "never send nothing");
        assertSame(bytes, r.bytes());
        assertTrue(r.note().toLowerCase().contains("no page matched"));
    }

    @Test
    void pdfAtOrUnderMinPagesIsSentWhole() throws IOException {
        byte[] bytes = pdf("Form 1040 Individual Income Tax Return", "State of Colorado Individual Return");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(8, 1));

        assertFalse(r.filtered());
        assertSame(bytes, r.bytes());
        assertEquals(2, r.pagesTotal());
    }

    @Test
    void aPageMatchingTwoRulesReportsBoth() throws IOException {
        // Real 1040-family schedules carry "(Form 1040)" in the masthead, so first-match-wins
        // would label a Schedule E page as "1040" in the report the analyst reads.
        byte[] bytes = pdf(
                "SCHEDULE E (Form 1040) Supplemental Income and Loss",
                "State of Colorado Individual Return",
                "State of Colorado page two");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertEquals(List.of("1040", "Schedule E"), r.matchedForms(),
                "every matching rule is reported, not just the first");
    }

    @Test
    void typographicApostropheInDocumentStillMatchesAsciiPattern() throws IOException {
        // IRS forms print a curly U+2019; an unfolded ASCII pattern would silently never match.
        byte[] bytes = pdf(
                "Partner’s Share of Income Deductions Credits",
                "State of Colorado Individual Return",
                "State of Colorado page two");
        PageSelectionProfile k1 = new PageSelectionProfile("test", 0, 0,
                List.of(new PageSelectionProfile.KeepRule("K-1", List.of("Partner's Share"))),
                List.of());

        PageSelectionService.SelectionResult r = service.select(bytes, k1);

        assertTrue(r.filtered());
        assertEquals(1, r.pagesKept());
        assertEquals(List.of("K-1"), r.matchedForms());
    }

    @Test
    void unreadableBytesAreSentWhole() {
        byte[] bytes = "this is not a pdf".getBytes(StandardCharsets.UTF_8);

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 1));

        assertFalse(r.filtered());
        assertSame(bytes, r.bytes());
        assertEquals(-1, r.pagesTotal(), "unknown total on a parse failure");
        assertEquals(-1, r.pagesKept(), "unknown kept count on a parse failure, never 0");
        assertNotNull(r.note());
    }

    @Test
    void inheritedResourcesSurviveTrimming() throws IOException {
        // Reproduces a legacy distiller/scanner shape: /Resources lives on the page-tree
        // node, not on the page dictionary itself. importPage-based trimming drops this
        // link; removePage-based trimming (in place, on the still-open source document)
        // keeps every surviving page's /Parent chain intact.
        byte[] bytes;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage schedulePage = new PDPage(PDRectangle.LETTER);
            doc.addPage(schedulePage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, schedulePage)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Form 1040 Individual Income Tax Return");
                cs.endText();
            }
            PDPage statePage = new PDPage(PDRectangle.LETTER);
            doc.addPage(statePage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, statePage)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("State of Colorado Individual Return");
                cs.endText();
            }

            // Move the federal page's own /Resources up onto the page-tree root, then
            // remove it from the page itself, so it can only be found by inheritance.
            COSDictionary sharedResources = schedulePage.getResources().getCOSObject();
            doc.getPages().getCOSObject().setItem(COSName.RESOURCES, sharedResources);
            schedulePage.getCOSObject().removeItem(COSName.RESOURCES);

            doc.save(bos);
            bytes = bos.toByteArray();
        }

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertTrue(r.filtered());
        assertEquals(1, r.pagesKept(), "only the federal page matches");
        try (PDDocument reloaded = Loader.loadPDF(r.bytes())) {
            assertNotNull(reloaded.getPage(0).getResources(),
                    "kept page must still resolve its inherited resources");
        }
        assertTrue(textOf(r.bytes()).contains("Form 1040"),
                "text extraction needs the font resource; garbled text means resources were lost");
    }

    @Test
    void headerSplitAcrossLinesStillMatches() throws IOException {
        // PDFTextStripper inserts a newline between separately positioned text runs, so a
        // masthead stacking "SCHEDULE" above "E" extracts as "SCHEDULE\nE".
        byte[] bytes;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage schedulePage = new PDPage(PDRectangle.LETTER);
            doc.addPage(schedulePage);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, schedulePage)) {
                cs.beginText();
                cs.setFont(font, 18);
                cs.newLineAtOffset(72, 720);
                cs.showText("SCHEDULE");
                cs.endText();
                cs.beginText();
                cs.setFont(font, 36);
                cs.newLineAtOffset(300, 690);
                cs.showText("E");
                cs.endText();
            }
            PDPage fillerPage = new PDPage(PDRectangle.LETTER);
            doc.addPage(fillerPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, fillerPage)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("unrelated filler text");
                cs.endText();
            }
            doc.save(bos);
            bytes = bos.toByteArray();
        }

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 0));

        assertTrue(r.filtered());
        assertEquals(1, r.pagesKept(), "the wrapped SCHEDULE / E header must still match as one page");
        assertEquals(List.of("Schedule E"), r.matchedForms());
    }

    @Test
    void continuationWindowIsRespected() throws IOException {
        byte[] bytes = pdf(
                "Form 1040 Individual Income Tax Return",
                "continuation page one, no header",
                "continuation page two, no header",
                "State of Colorado Individual Return");

        PageSelectionService.SelectionResult r = service.select(bytes, profile(0, 1));

        assertEquals(2, r.pagesKept(), "a window of 1 keeps only the immediate next page");
        String kept = textOf(r.bytes());
        assertTrue(kept.contains("continuation page one"));
        assertFalse(kept.contains("continuation page two"));
    }
}
