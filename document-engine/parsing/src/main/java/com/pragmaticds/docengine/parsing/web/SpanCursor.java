package com.pragmaticds.docengine.parsing.web;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.repo.TextSpanRow;
import com.pragmaticds.docengine.parsing.repo.TextSpanWindow;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The opaque {@code ?after=} token: a position in ONE window's total order.
 *
 * <p>This is the repository's first pagination convention, so it is worth saying what a cursor here
 * is and is not. It is a POSITION, not a snapshot: it names the last row already served, and the
 * next request resumes strictly after it. Concurrent READS cannot disturb it, because nothing about
 * a read is stateful and the position lives entirely in the caller's hand.
 *
 * <h2>What it is NOT safe against, precisely</h2>
 *
 * <p>L1 is not append-only. {@code TextSpanService.deleteBySource} runs on EVERY TEXT_EXTRACTION and
 * OCR_PROCESSING run, including a resume or a retry: it deletes that source's spans across the
 * package and re-inserts them with fresh identity ids, while the {@code page} row and its id survive.
 * And {@code content_hash} covers span (text, box) only — never span ids — so a re-run that
 * reproduces identical words leaves the digest, and therefore the ETag, byte-identical while every
 * span id the caller already collected has been replaced. A walk that spans such a re-run comes back
 * with no duplicate and no gap in COUNT and no change in the pin; what it loses is the meaning of the
 * ids it already holds, which is what {@code evidence[].textSpanId} and {@code ?element=} join on. The
 * pin catches a re-parse that CHANGED the page. It cannot catch one that did not.
 *
 * <h2>What the token pins, and why</h2>
 *
 * <p>It pins everything that defines the ORDER — the mode, the page, the element in element mode,
 * and the cursor's own source in page mode — and refuses a token that does not match the request it
 * arrived on. That is not decoration: a page cursor's ordinal is a {@code text_span.ordinal} while
 * an element cursor's is a {@code layout_element_span.ordinal}, and replaying one as the other would
 * skip or repeat rows while returning a perfectly plausible 200.
 *
 * <p>It deliberately does NOT pin the filters, and the reason holds in ONE direction only.
 * NARROWING mid-walk is coherent: the narrower window is a subset in the same order, so the
 * continuation resumes exactly where the caller was. WIDENING is not, and the server cannot say so —
 * a cursor parked in the OCR block sorts after every NATIVE row, so dropping {@code ?source=OCR}
 * mid-walk does not go back for the NATIVE block; it walks to exhaustion and ends on
 * {@code truncated: false} having skipped it. The token carries the source of the last ROW served,
 * not the filter that was applied, so the two cases are indistinguishable here. Narrow mid-walk if
 * you must; to widen, start a new walk. {@code PageSpansPaginationIT} measures both.
 *
 * <p>It is not signed and needs no secret. It carries a page id the caller already named in the
 * path, and every read re-checks org and tombstone from scratch, so forging one buys nothing that
 * asking directly would not.
 */
record SpanCursor(
        TextSpanWindow.Mode mode, UUID pageId, UUID elementId, SpanSource source, int ordinal, long spanId) {

    private static final String VERSION = "1";
    private static final String SEPARATOR = "|";
    private static final String ABSENT = "-";
    private static final int FIELDS = 7;

    /** Issues the cursor that resumes strictly after this row of that window. */
    static String encode(TextSpanWindow window, TextSpanRow row) {
        String raw =
                String.join(
                        SEPARATOR,
                        VERSION,
                        window.mode().name(),
                        window.pageId().toString(),
                        window.elementId() == null ? ABSENT : window.elementId().toString(),
                        row.span().getSource().name(),
                        String.valueOf(row.sortOrdinal()),
                        String.valueOf(row.span().getId()));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a token back, or refuses. Every failure is the same opaque {@code INVALID_REQUEST}
     * naming the PARAMETER and never the value — decoding a cursor is not an oracle for which pages
     * exist, and an error message is not a leak channel.
     */
    static SpanCursor decode(String token, TextSpanWindow window) {
        String[] parts = split(token);
        SpanCursor cursor =
                new SpanCursor(
                        mode(parts[1]),
                        uuid(parts[2]),
                        ABSENT.equals(parts[3]) ? null : uuid(parts[3]),
                        source(parts[4]),
                        integer(parts[5]),
                        number(parts[6]));

        if (cursor.mode() != window.mode()
                || !cursor.pageId().equals(window.pageId())
                || !Objects.equals(cursor.elementId(), window.elementId())) {
            throw invalid("cursorDoesNotMatchWindow");
        }
        return cursor;
    }

    /** Applies this position to the window it was issued for. */
    TextSpanWindow resume(TextSpanWindow window) {
        return mode == TextSpanWindow.Mode.ELEMENT
                ? window.afterElementMember(ordinal, spanId)
                : window.afterPagePosition(source, ordinal, spanId);
    }

    private static String[] split(String token) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw invalid("cursorEncoding");
        }
        String[] parts = new String(decoded, StandardCharsets.UTF_8).split("\\" + SEPARATOR, -1);
        if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
            throw invalid("cursorShape");
        }
        return parts;
    }

    private static TextSpanWindow.Mode mode(String raw) {
        try {
            return TextSpanWindow.Mode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("cursorMode");
        }
    }

    private static SpanSource source(String raw) {
        try {
            return SpanSource.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("cursorSource");
        }
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("cursorId");
        }
    }

    private static int integer(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw invalid("cursorOrdinal");
        }
    }

    private static long number(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalid("cursorSpanId");
        }
    }

    private static DomainException invalid(String reason) {
        return DomainException.badRequest(ErrorCode.INVALID_REQUEST, Map.of("parameter", reason));
    }
}
