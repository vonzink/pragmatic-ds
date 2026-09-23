package com.pragmaticds.docengine.ingestion.validate;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.io.IOException;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * Ingestion-time PDF validation: can the file be opened, is it password-protected, and how many
 * pages does it have. Parsing proper belongs to the Python worker — this exists so a corrupt or
 * encrypted file is rejected at upload with a stable error code instead of failing five stages
 * later inside the pipeline.
 *
 * <p>Plain class, no Spring: the page cap is a constructor argument so callers (and tests) choose
 * the limit without a container.
 *
 * <p>PDFBox throws undocumented RuntimeExceptions on fuzzed input, not just IOException. ALL of
 * them collapse to {@link ErrorCode#CORRUPT_PDF} here — a raw parser message can quote document
 * bytes, and nothing sensitive may ride an exception out of this class.
 */
public class PdfProbe {

    /**
     * What ingestion needs to know about a PDF that passed validation.
     *
     * <p>{@code encrypted} means owner-password-only: the file carries encryption on disk but
     * opened with the empty user password, so its content is readable. It is recorded, not acted
     * on — a reviewer looking at a lender-supplied document deserves to know it arrived encrypted.
     */
    public record Result(int pageCount, boolean encrypted) {}

    private final int maxPages;

    public PdfProbe(int maxPages) {
        this.maxPages = maxPages;
    }

    /**
     * @throws DomainException {@code PASSWORD_PROTECTED} for files that need a user password we do
     *     not have, {@code CORRUPT_PDF} for anything PDFBox cannot open, {@code
     *     PAGE_LIMIT_EXCEEDED} above the cap. Owner-password-only files are NOT rejected — they
     *     open with the empty user password and come back with {@code encrypted} set.
     */
    public Result probe(byte[] content) {
        int pageCount;
        boolean encrypted;
        try (PDDocument document = Loader.loadPDF(content)) {
            // Reaching this line means PDFBox ALREADY decrypted the file — with the empty user
            // password, since no password was supplied. That is owner-password-only encryption:
            // printing/editing are restricted, reading is not, which is why such files open with
            // no prompt in every viewer. They are ubiquitous among bank statements, payroll
            // paystubs and lender-generated closing documents. The document in hand holds
            // plaintext content streams, so the pipeline reads exactly what a viewer would.
            // A file that genuinely needs a user password never gets here — Loader throws
            // InvalidPasswordException, caught below.
            pageCount = document.getNumberOfPages();
            encrypted = document.isEncrypted();
        } catch (InvalidPasswordException e) {
            throw DomainException.badRequest(ErrorCode.PASSWORD_PROTECTED, Map.of());
        } catch (DomainException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw DomainException.badRequest(ErrorCode.CORRUPT_PDF, Map.of());
        }
        if (pageCount > maxPages) {
            throw DomainException.badRequest(
                    ErrorCode.PAGE_LIMIT_EXCEEDED, Map.of("pageCount", pageCount, "maxPages", maxPages));
        }
        return new Result(pageCount, encrypted);
    }
}
