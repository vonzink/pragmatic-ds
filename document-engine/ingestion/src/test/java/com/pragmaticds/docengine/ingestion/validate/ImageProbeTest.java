package com.pragmaticds.docengine.ingestion.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The image twin of {@link PdfProbeTest}: fixtures are drawn in-test with ImageIO, never committed
 * as binaries.
 *
 * <p>Two facts this probe exists to establish, both of which were missing when four JPEG paystubs
 * were uploaded: a {@code page_count} (the column was NULL for every image even though validation
 * passed), and a decode verdict at the door, so an image that cannot be read is rejected at upload
 * with {@link ErrorCode#CORRUPT_IMAGE} instead of failing five stages later.
 *
 * <p>HEIC is the exception to the second fact, and the tests below say so out loud rather than
 * leaving it to be discovered: the JVM has no HEIF reader, so a HEIC is accepted on its magic bytes
 * and the worker is its only decode gate.
 */
class ImageProbeTest {

    private static final int GENEROUS_CAP = 500;

    private final ImageProbe probe = new ImageProbe(GENEROUS_CAP);

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.drawString("Gross Pay $2,450.00", 20, 40);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** A multi-page TIFF — what a scanner or fax gateway produces for a two-page document. */
    private static byte[] multiPageTiff(int pages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int page = 0; page < pages; page++) {
                BufferedImage frame = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    @Test
    void a_jpeg_photo_is_a_one_page_document() throws IOException {
        assertThat(probe.probe(image("jpeg", 1224, 1584), "image/jpeg").pageCount()).isEqualTo(1);
    }

    @Test
    void a_png_screenshot_is_a_one_page_document() throws IOException {
        assertThat(probe.probe(image("png", 800, 1000), "image/png").pageCount()).isEqualTo(1);
    }

    @Test
    void a_multi_page_tiff_reports_every_frame() throws IOException {
        // Reporting 1 here would let two thirds of a three-page fax vanish with no error.
        assertThat(probe.probe(multiPageTiff(3), "image/tiff").pageCount()).isEqualTo(3);
    }

    /** A HEIC's leading bytes: box size, {@code ftyp}, then the {@code heic} major brand. */
    private static byte[] heicHeader(byte... body) {
        byte[] head = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        byte[] file = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, file, head.length, body.length);
        return file;
    }

    @Test
    void a_heic_is_a_one_page_document() {
        // No JVM decoder exists for HEIF — ImageIO ships none and there is no permissive
        // plugin — so the page count is asserted from the format, not measured. One is right
        // for the same reason it is right for a JPEG: a burst or Live Photo HEIC carries
        // several coded images but is one picture, and only TIFF's frames are pages.
        assertThat(probe.probe(heicHeader(), "image/heic").pageCount()).isEqualTo(1);
    }

    @Test
    void a_heic_is_accepted_undecoded_while_the_same_bytes_under_another_type_are_corrupt() {
        // The whole asymmetry in one assertion, and the reason the content type had to become
        // an argument: identical bytes, opposite verdicts. Under image/heic the probe declines
        // to judge and the worker (source.py) rejects a bad file at PARSE as CORRUPT_IMAGE;
        // under any type the JVM can actually read, undecodable bytes are still refused here.
        //
        // Asking ImageIO about a HEIC instead would return CORRUPT_IMAGE for a borrower's
        // perfectly good iPhone photo — the exact mis-diagnosis this class was written to end.
        byte[] headerOnly = heicHeader();

        assertThat(probe.probe(headerOnly, "image/heic").pageCount()).isEqualTo(1);

        assertThatThrownBy(() -> probe.probe(headerOnly, "image/jpeg"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CORRUPT_IMAGE));
    }

    @Test
    void a_truncated_jpeg_is_accepted_here_because_a_decoder_still_reads_it() throws IOException {
        // Pins the documented division of labour (see ImageProbe's javadoc), because the two
        // decoders genuinely disagree and the disagreement must be a decision, not a surprise:
        // ImageIO reconstructs a truncated JPEG (warning: "Truncated File - Missing EOI marker")
        // while Pillow refuses it outright. Rejecting on ImageIO warnings was considered and
        // dropped — real camera JPEGs warn about things like invalid ICC profiles, and refusing a
        // borrower's genuine photo at upload is worse than the worker refusing it by name.
        byte[] whole = image("jpeg", 1224, 1584);
        byte[] truncated = Arrays.copyOf(whole, whole.length / 4);

        assertThat(probe.probe(truncated, "image/jpeg").pageCount()).isEqualTo(1);
    }

    @Test
    void an_undecodable_image_is_never_reported_as_a_corrupt_pdf() {
        byte[] jpegHeaderThenGarbage = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0};

        DomainException exception =
                catchThrowableOfType(DomainException.class, () -> probe.probe(jpegHeaderThenGarbage, "image/jpeg"));

        assertThat(exception.code()).isEqualTo(ErrorCode.CORRUPT_IMAGE);
        assertThat(exception.code()).isNotEqualTo(ErrorCode.CORRUPT_PDF);
    }

    @Test
    void corrupt_error_params_never_carry_document_bytes() {
        byte[] garbage =
                ("ÿØÿ SSN 123-45-6789").getBytes(StandardCharsets.ISO_8859_1);

        DomainException exception =
                catchThrowableOfType(DomainException.class, () -> probe.probe(garbage, "image/jpeg"));

        assertThat(exception.params().toString()).doesNotContain("123-45-6789");
        assertThat(exception.getMessage()).doesNotContain("123-45-6789");
    }

    @Test
    void empty_bytes_are_corrupt_image_not_a_crash() {
        assertThatThrownBy(() -> probe.probe(new byte[0], "image/jpeg"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CORRUPT_IMAGE));
    }

    @Test
    void a_tiff_over_the_page_cap_is_rejected_with_both_numbers() throws IOException {
        ImageProbe capped = new ImageProbe(2);

        DomainException exception =
                catchThrowableOfType(DomainException.class, () -> capped.probe(multiPageTiff(3), "image/tiff"));

        assertThat(exception.code()).isEqualTo(ErrorCode.PAGE_LIMIT_EXCEEDED);
        assertThat(exception.params()).containsEntry("pageCount", 3).containsEntry("maxPages", 2);
    }
}
