package com.pragmaticds.docengine.platform.ai;

import java.util.Objects;

/**
 * The SECOND-PASS extraction provider (Phase G): the same port contract wrapped in its own type,
 * so the stronger-model adapter and the first-pass adapter can coexist as Spring beans without
 * qualifier gymnastics — and so a test can replace exactly one of them.
 *
 * <p>Why a stronger tier at all: escalating a shaky value to the same cheap reader that produced
 * it buys nothing. The SELECTOR'S narrowness — only documents that still carry a missing or
 * review-flagged field, only after the first pass has done what it can — is what keeps Pro-tier
 * pennies-per-package.
 */
public record AiSecondPass(AiExtractionPort port) {

    public AiSecondPass {
        Objects.requireNonNull(port, "port");
    }
}
