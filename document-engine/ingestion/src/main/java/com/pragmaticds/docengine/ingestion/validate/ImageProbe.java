package com.pragmaticds.docengine.ingestion.validate;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Ingestion-time image validation — the twin of {@link PdfProbe}: can the file be decoded, and how
 * many pages does it hold.
 *
 * <p>Both answers were missing before. {@code source_file.page_count} was NULL for every image
 * ("images pass through with no page count until the Phase 2 normalization worker converts and
 * counts them" — a worker that never arrived), and an undecodable image was accepted at the door
 * and blew up five stages later as a CORRUPT_PDF, which named neither the file nor the problem.
 *
 * <p>Page count is FRAME count. One JPEG or PNG is one page; a multi-frame TIFF is what a scanner
 * or fax gateway emits for a multi-page document, and reporting 1 for a three-page fax would let
 * two thirds of it vanish with no error anywhere.
 *
 * <p>Plain class, no Spring: the page cap is a constructor argument, same shape as {@link PdfProbe}.
 *
 * <p>The first frame is genuinely DECODED, not just header-parsed: a header parses for bytes that
 * hold no image at all, and every decoder failure — {@link IOException} and the undocumented
 * RuntimeExceptions ImageIO plugins throw on fuzzed input alike — collapses to {@link
 * ErrorCode#CORRUPT_IMAGE}, because a raw decoder message can quote file bytes. Cost is bounded by
 * the upload size cap, the same exposure PDFBox already carries in {@link PdfProbe}.
 *
 * <p><b>Division of labour with the worker, stated because the two decoders disagree.</b> This
 * probe asks "can any decoder read this at all"; the worker (Pillow, {@code source.py}) is the
 * strict gate and refuses anything short of a complete decode. A JPEG truncated mid-scan passes
 * here — ImageIO reconstructs it, warning "Truncated File - Missing EOI marker" — and is rejected
 * by the worker as CORRUPT_IMAGE. Tightening this side to reject on ImageIO warnings was
 * considered and dropped: real camera JPEGs warn about benign things (invalid ICC profiles among
 * them), and refusing a borrower's genuine photo at upload is a worse failure than the worker
 * refusing a damaged one by name. Either way the code is CORRUPT_IMAGE and the stage row says so —
 * which is the whole difference from the defect that prompted this class, where a JPEG failed as a
 * "corrupt PDF" three times over.
 *
 * <p><b>HEIC has no decode verdict at all, and that is the third case.</b> The JVM cannot read
 * HEIF: ImageIO ships no plugin and no permissive one exists (the encoders and decoders in that
 * ecosystem are LGPL or GPL, and the one wheel the worker uses is decode-only — docs/LICENSING.md
 * 5.4). Asking ImageIO anyway would return CORRUPT_IMAGE for a borrower's perfectly good iPhone
 * photo, which is precisely the mis-diagnosis this class exists to end. So a HEIC is accepted on
 * its magic bytes with a page count of 1 and NOT decoded, and the worker becomes its only decode
 * gate: a truncated HEIC passes upload and fails at PARSE as CORRUPT_IMAGE. Same error code, later
 * stage, still named correctly — which is why this is a stated trade and not a hole.
 */
public class ImageProbe {

    /** What ingestion needs to know about an image that passed validation. */
    public record Result(int pageCount) {}

    /**
     * The sniffed types no JVM decoder can open. Sniffed, never claimed — {@link MimeSniffer} read
     * it off the bytes.
     */
    private static final Set<String> NO_JVM_DECODER = Set.of("image/heic");

    /**
     * A HEIC is one page. A burst or Live Photo carries several coded images, but that is one
     * picture and not several pages — the same rule the worker applies, where frames are a page
     * concept for TIFF alone.
     */
    private static final int HEIF_PAGE_COUNT = 1;

    private final int maxPages;

    public ImageProbe(int maxPages) {
        this.maxPages = maxPages;
    }

    /**
     * @param contentType the type {@link MimeSniffer} read off the bytes. It decides whether a
     *     decode is even attempted, so passing a client's claim here would let a caller talk this
     *     probe out of validating a file.
     * @throws DomainException {@code CORRUPT_IMAGE} for bytes no decoder can read, {@code
     *     PAGE_LIMIT_EXCEEDED} for a multi-frame file above the cap.
     */
    public Result probe(byte[] content, String contentType) {
        if (NO_JVM_DECODER.contains(contentType)) {
            return new Result(HEIF_PAGE_COUNT);
        }
        int pageCount = readFrameCount(content);
        if (pageCount > maxPages) {
            throw DomainException.badRequest(
                    ErrorCode.PAGE_LIMIT_EXCEEDED,
                    Map.of("pageCount", pageCount, "maxPages", maxPages));
        }
        return new Result(pageCount);
    }

    private int readFrameCount(byte[] content) {
        // MemoryCacheImageInputStream, not ImageIO.createImageInputStream: the latter may spill to
        // a temp FILE depending on the JVM-wide cache setting, and borrower document bytes must
        // never touch disk outside the storage port.
        try (ImageInputStream stream =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw corrupt();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                // `true` = walk the whole directory. A TIFF reports its real frame count here;
                // single-frame formats report 1.
                int frames = reader.getNumImages(true);
                // Header-only success is not success: decode frame 0 so a truncated file is
                // rejected here rather than at RENDERING.
                reader.read(0);
                if (frames <= 0) {
                    throw corrupt();
                }
                return frames;
            } finally {
                reader.dispose();
            }
        } catch (DomainException alreadyStable) {
            throw alreadyStable;
        } catch (IOException | RuntimeException undecodable) {
            throw corrupt();
        }
    }

    private static DomainException corrupt() {
        // No params: everything ImageIO could tell us about the failure is derived from the file's
        // own bytes (ARCHITECTURE.md 10).
        return DomainException.badRequest(ErrorCode.CORRUPT_IMAGE, Map.of());
    }
}
