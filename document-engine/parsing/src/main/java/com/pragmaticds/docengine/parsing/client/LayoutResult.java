package com.pragmaticds.docengine.parsing.client;

import java.util.List;

/**
 * Typed {@code POST /v1/layout} response. {@code rawJson} is the response body verbatim — it is
 * what {@code parser_output} blobs, so the raw record is exactly what the worker said (including
 * {@code notImplemented}), not a re-serialization.
 */
public record LayoutResult(WorkerBlock worker, List<LayoutPage> pages, String rawJson) {}
