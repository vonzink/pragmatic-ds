package com.pragmaticds.docengine.platform.behavior;

import java.util.List;

/**
 * A loader's currently-HELD behavior snapshot, read PURELY and as one thing: its identity {@code
 * token} and the fingerprint {@code view} that token names, captured together from a single cache
 * read.
 *
 * <p>This pairing is the close of the guard→compose TOCTOU. A completed run's finalizer must both
 * (a) confirm the token still matches what the run recorded, and (b) compose the stamp from the
 * view. Reading the token and the view separately opens a window: the cache can be replaced between
 * the two reads, so the guard validates one view while the stamp is composed from another. Bound
 * into one object read once, the token that is validated and the view that is stamped are, by
 * construction, the same snapshot — the bytes stamped are always the bytes of the view the guard
 * proved the run executed under.
 *
 * @param token the snapshot's identity, compared against {@link BehaviorViewScope#recorded}
 * @param view the behavior-relevant rows that token names, composed verbatim into the fingerprint
 * @param <T> the loader's per-row fingerprint type (a pack row's or a schema row's identity)
 */
public record HeldBehaviorView<T>(String token, List<T> view) {}
