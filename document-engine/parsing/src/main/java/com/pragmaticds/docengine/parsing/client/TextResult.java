package com.pragmaticds.docengine.parsing.client;

import java.util.List;

/**
 * Typed {@code POST /v1/text} response. {@code rawJson} is the response body verbatim — it is what
 * {@code parser_output} blobs, so the raw record is exactly what the worker said, not a re-serialization.
 */
public record TextResult(WorkerBlock worker, List<TextPage> pages, String rawJson) {}
