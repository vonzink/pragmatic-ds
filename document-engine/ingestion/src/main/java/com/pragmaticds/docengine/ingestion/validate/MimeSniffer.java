package com.pragmaticds.docengine.ingestion.validate;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identifies a file's type from its magic bytes — the client's declared content type is never
 * consulted, because it is an unauthenticated claim (docs/DATA_MODEL.md: {@code content_type} is
 * "sniffed from magic bytes, not the client's claim"). A miss returns empty rather than guessing;
 * the caller decides that an unrecognised file is an {@code UNSUPPORTED_MIME} rejection.
 */
@Component
public class MimeSniffer {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    // The FULL eight-byte signature. The first four alone accept junk as an image
    // (review finding); the trailing \r\n\x1a\n bytes are part of the spec exactly
    // to catch transfer corruption.
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] TIFF_LE = {0x49, 0x49, 0x2A, 0x00};
    private static final byte[] TIFF_BE = {0x4D, 0x4D, 0x00, 0x2A};

    // HEIC is ISO-BMFF, so unlike every format above it has no fixed prefix: bytes 0..4 are a box
    // length that varies with the file. `ftyp` sits at 4..8 and the major brand at 8..12.
    private static final int FTYP_OFFSET = 4;
    private static final int BRAND_OFFSET = 8;
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};

    // heic: what an iPhone writes by default. heix: its 10-bit sibling. hevc: an image sequence.
    // mif1: the generic HEIF brand some Android cameras write. All four are one MIME type here
    // because all four decode by the same path in the worker and the brand is preserved nowhere
    // downstream — a second string would only widen every accept-list in the codebase.
    private static final byte[][] HEIF_BRANDS = {
        {'h', 'e', 'i', 'c'},
        {'h', 'e', 'i', 'x'},
        {'h', 'e', 'v', 'c'},
        {'m', 'i', 'f', '1'}
    };

    /** @return the sniffed MIME type, or empty when the leading bytes match no supported format. */
    public Optional<String> sniff(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, PDF)) {
            return Optional.of("application/pdf");
        }
        if (startsWith(content, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(content, TIFF_LE) || startsWith(content, TIFF_BE)) {
            return Optional.of("image/tiff");
        }
        if (isHeif(content)) {
            return Optional.of("image/heic");
        }
        return Optional.empty();
    }

    /**
     * Matches on the MAJOR brand only. The compatible-brand list that follows it is deliberately
     * NOT scanned: a real AVIF names {@code mif1} among its compatible brands, and the worker's
     * libheif carries libde265 alone — no AVIF decoder — so scanning that list would accept at the
     * door a file that cannot be decoded at PARSE. What ingestion accepts stays exactly what the
     * worker can read.
     */
    private static boolean isHeif(byte[] content) {
        if (!matchesAt(content, FTYP_OFFSET, FTYP)) {
            return false;
        }
        for (byte[] brand : HEIF_BRANDS) {
            if (matchesAt(content, BRAND_OFFSET, brand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return matchesAt(content, 0, magic);
    }

    private static boolean matchesAt(byte[] content, int offset, byte[] magic) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
