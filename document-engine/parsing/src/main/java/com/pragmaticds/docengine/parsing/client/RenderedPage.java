package com.pragmaticds.docengine.parsing.client;

/** Metadata plus the PNG bytes of the multipart part it named. */
public record RenderedPage(RenderPageMeta meta, byte[] png) {}
