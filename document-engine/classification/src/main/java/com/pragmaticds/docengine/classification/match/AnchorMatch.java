package com.pragmaticds.docengine.classification.match;

import java.util.List;

/**
 * One anchor's outcome on one page: whether it hit, the {@code text_span} ids and boxes of ALL
 * spans the hit overlaps, and the matched OFFSET range in the joined reading-order text. Offsets,
 * ids, and boxes only — never the matched text itself, so evidence built from this can carry no
 * document content by construction.
 *
 * @param rangeStart inclusive offset in the joined text, null on a miss
 * @param rangeEnd exclusive offset, null on a miss
 */
public record AnchorMatch(
        String anchorId,
        boolean matched,
        List<Long> spanIds,
        List<Box> boxes,
        Integer rangeStart,
        Integer rangeEnd) {

    static AnchorMatch miss(String anchorId) {
        return new AnchorMatch(anchorId, false, List.of(), List.of(), null, null);
    }
}
