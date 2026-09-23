package com.pragmaticds.docengine.parsing.client;

import java.util.List;

/** Typed {@code POST /v1/render} response: version block + pages in metadata order. */
public record RenderResult(WorkerBlock worker, List<RenderedPage> pages) {}
