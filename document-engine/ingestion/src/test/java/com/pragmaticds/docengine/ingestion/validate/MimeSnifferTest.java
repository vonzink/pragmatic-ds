package com.pragmaticds.docengine.ingestion.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The sniffer decides from magic bytes ONLY — the client's declared content type never reaches it.
 * The spoof cases are the reason it exists: a `.pdf` upload carrying PNG bytes must be identified
 * as a PNG, and text bytes claiming to be a PDF must be identified as nothing at all.
 */
class MimeSnifferTest {

    private final MimeSniffer sniffer = new MimeSniffer();

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @Test
    void pdf_magic_is_application_pdf() {
        byte[] content = "%PDF-1.7\nrest of file".getBytes(StandardCharsets.US_ASCII);

        assertThat(sniffer.sniff(content)).contains("application/pdf");
    }

    @Test
    void png_magic_is_image_png() {
        byte[] content = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00);

        assertThat(sniffer.sniff(content)).contains("image/png");
    }

    @Test
    void jpeg_magic_is_image_jpeg() {
        byte[] content = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10);

        assertThat(sniffer.sniff(content)).contains("image/jpeg");
    }

    @Test
    void tiff_little_endian_magic_is_image_tiff() {
        byte[] content = bytes(0x49, 0x49, 0x2A, 0x00, 0x08);

        assertThat(sniffer.sniff(content)).contains("image/tiff");
    }

    @Test
    void tiff_big_endian_magic_is_image_tiff() {
        byte[] content = bytes(0x4D, 0x4D, 0x00, 0x2A, 0x08);

        assertThat(sniffer.sniff(content)).contains("image/tiff");
    }

    /**
     * An ISO-BMFF {@code ftyp} box with {@code majorBrand} as the MAJOR brand: a big-endian box
     * size, then {@code ftyp}, then the major brand, then a minor version and the compatible-brand
     * list. {@code mif1} always sits in the compatible list — the brand a sniffer that read the
     * wrong list would find.
     */
    private static byte[] ftyp(String majorBrand) {
        byte[] body =
                ("ftyp" + majorBrand + "\0\0\0\0" + "mif1" + majorBrand)
                        .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(body.length + 4).putInt(body.length + 4).put(body).array();
    }

    @Test
    void the_heif_brands_a_phone_camera_writes_are_image_heic() {
        // HEIC's identity is not a prefix: bytes 0..4 are a box length that varies with the
        // file, so the brand at 8..12 is the signature. `heic` is what an iPhone writes by
        // default, `heix` its 10-bit sibling, `hevc` an image sequence, `mif1` the generic
        // HEIF brand. All four decode identically in the worker, so all four are one type.
        assertThat(sniffer.sniff(ftyp("heic"))).contains("image/heic");
        assertThat(sniffer.sniff(ftyp("heix"))).contains("image/heic");
        assertThat(sniffer.sniff(ftyp("hevc"))).contains("image/heic");
        assertThat(sniffer.sniff(ftyp("mif1"))).contains("image/heic");
    }

    @Test
    void an_avif_is_unrecognised_despite_declaring_mif1_compatibility() {
        // The reason only the MAJOR brand is consulted. A real AVIF lists `mif1` among its
        // compatible brands, so reading that list would accept AVIF here — and the worker's
        // libheif ships libde265 only, no AVIF decoder, so ingestion would be accepting at the
        // door what cannot be decoded at PARSE. Unsupported at upload is the honest answer.
        assertThat(sniffer.sniff(ftyp("avif"))).isEmpty();
        assertThat(sniffer.sniff(ftyp("avis"))).isEmpty();
        assertThat(sniffer.sniff(ftyp("mp42"))).isEmpty();
        assertThat(sniffer.sniff(ftyp("isom"))).isEmpty();
    }

    @Test
    void a_truncated_ftyp_box_is_unrecognised() {
        // Twelve bytes must be present before a brand can be read. Guessing from fewer would
        // accept any file whose fifth byte happens to be 'f'.
        assertThat(sniffer.sniff(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e'))).isEmpty();
        assertThat(sniffer.sniff(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p'))).isEmpty();
        assertThat(sniffer.sniff(bytes('f', 't', 'y', 'p', 'h', 'e', 'i', 'c'))).isEmpty();
    }

    @Test
    void plain_text_spoofing_a_pdf_is_unrecognised() {
        byte[] content = "just a text file pretending to be borrower.pdf".getBytes(StandardCharsets.US_ASCII);

        assertThat(sniffer.sniff(content)).isEmpty();
    }

    @Test
    void png_bytes_are_png_no_matter_what_the_client_claims() {
        // The sniffer never sees the claim — this documents that bytes alone decide.
        byte[] content = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

        assertThat(sniffer.sniff(content)).contains("image/png");
    }

    @Test
    void truncated_magic_is_unrecognised() {
        assertThat(sniffer.sniff("%PD".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(sniffer.sniff(bytes(0x89, 0x50))).isEmpty();
    }

    @Test
    void magic_not_at_offset_zero_is_unrecognised() {
        byte[] content = " %PDF-1.4".getBytes(StandardCharsets.US_ASCII);

        assertThat(sniffer.sniff(content)).isEmpty();
    }

    @Test
    void png_requires_the_full_eight_byte_signature() {
        // Review finding: only 4 of PNG's 8 signature bytes were checked, so junk that happens
        // to start 89 50 4E 47 was accepted as an image and shipped down the pipeline.
        byte[] fourByteImpostor = bytes(0x89, 0x50, 0x4E, 0x47, 0x00, 0x00, 0x00, 0x00);
        byte[] realSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

        assertThat(sniffer.sniff(fourByteImpostor)).isEmpty();
        assertThat(sniffer.sniff(realSignature)).contains("image/png");
    }

    @Test
    void empty_and_null_are_unrecognised() {
        assertThat(sniffer.sniff(new byte[0])).isEmpty();
        assertThat(sniffer.sniff(null)).isEmpty();
    }
}
