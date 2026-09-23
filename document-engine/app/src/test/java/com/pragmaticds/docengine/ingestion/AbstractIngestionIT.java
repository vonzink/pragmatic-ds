package com.pragmaticds.docengine.ingestion;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared plumbing for the ingestion HTTP integration tests: MockMvc through the real filter chain
 * (DevTenantFilter binds the dev org exactly as in a deployment), a recording ProcessingStarter to
 * observe the orchestration seam, and PDFBox-generated fixtures so no binary blobs live in the
 * repo.
 *
 * <p>Subclasses share one Spring context as long as they add no annotations of their own — keep
 * new cap-override tests in {@code PackageUploadLimitsIT}, which pays for its own context.
 */
@AutoConfigureMockMvc
@Import(AbstractIngestionIT.SeamRecorderConfig.class)
@TestPropertySource(properties = "docengine.storage.local-root=build/test-blobs")
public abstract class AbstractIngestionIT extends AbstractPostgresIT {

    @TestConfiguration
    static class SeamRecorderConfig {
        /** @Primary so it wins over the app's seam placeholder in every IT that imports it. */
        @Bean
        @Primary
        RecordingProcessingStarter recordingProcessingStarter() {
            return new RecordingProcessingStarter();
        }
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected RecordingProcessingStarter startedJobs;
    @Autowired protected BlobStoragePort blobStorage;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void ingestionSetup() {
        jdbc = new JdbcTemplate(dataSource);
        startedJobs.reset();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    protected static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    protected static byte[] encryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Owner password set, user password EMPTY — printing/editing restricted, reading is not. Opens
     * with no prompt in any viewer, which is why bank statements, payroll-provider paystubs and
     * lender-generated closing documents ship this way. Must be ACCEPTED at upload.
     */
    protected static byte[] ownerPasswordOnlyPdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.protect(new StandardProtectionPolicy("owner-secret", "", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * A REAL image, drawn in-test. It used to be magic bytes plus filler, which was enough while
     * "Phase 1 never decodes images" held — ingestion now probes images the same way it probes
     * PDFs, so a fixture that is not an image is no longer a fixture for an accepted upload.
     */
    protected static byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.drawString("Gross Pay $2,450.00", 8, height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /**
     * A real PNG padded to EXACTLY {@code totalLength} bytes when that is larger, so size-cap tests
     * keep naming an exact number. Decoders stop at IEND, so the padding is invisible to them.
     */
    protected static byte[] pngBytes(int totalLength) throws IOException {
        byte[] real = imageBytes("png", 40, 52);
        return totalLength <= real.length ? real : Arrays.copyOf(real, totalLength);
    }

    protected static byte[] jpegBytes() throws IOException {
        return imageBytes("jpeg", 48, 62);
    }

    /** Image magic with nothing decodable behind it — an upload that must be REJECTED. */
    protected static byte[] undecodableJpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    }

    /**
     * A HEIC's leading bytes — box size, {@code ftyp}, then the {@code heic} major brand — followed
     * by filler.
     *
     * <p>This is a HEADER, not a photo, and that is faithful rather than lazy: ingestion never
     * decodes a HEIC (no JVM HEIF reader exists — see the ImageProbe javadoc), so a real HEVC
     * payload would exercise nothing here that these bytes do not. The decode itself is proven
     * against a genuine HEIC in {@code worker/tests/image/}, which is where the decoder lives.
     */
    protected static byte[] heicBytes() {
        byte[] head = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        byte[] file = Arrays.copyOf(head, 512);
        Arrays.fill(file, head.length, file.length, (byte) 0x11);
        return file;
    }

    /** A multi-frame TIFF — a scanner's or fax gateway's multi-page document. */
    protected static byte[] tiffBytes(int frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int frame = 0; frame < frames; frame++) {
                writer.writeToSequence(
                        new IIOImage(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), null, null),
                        null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    protected static byte[] textBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    protected static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static MockMultipartFile filePart(String filename, String declaredType, byte[] bytes) {
        return new MockMultipartFile("files", filename, declaredType, bytes);
    }
}
