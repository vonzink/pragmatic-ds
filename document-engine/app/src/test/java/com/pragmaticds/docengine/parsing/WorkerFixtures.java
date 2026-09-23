package com.pragmaticds.docengine.parsing;

import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;

/**
 * Contract-shaped MockWebServer fixtures (docs/WORKER_CONTRACT.md). The JSON text lives in the
 * tests; this helper only assembles the {@code /v1/render} multipart/mixed body, whose framing
 * matches what the Java client parses: parts named via {@code Content-Disposition}, metadata
 * first, then one {@code image/png} part per page.
 */
final class WorkerFixtures {

    static final String RENDER_WORKER_BLOCK =
            "{ \"version\": \"0.2.0\", \"libraries\": {\"pypdfium2\": \"5.12.1\"} }";

    private WorkerFixtures() {}

    /**
     * A render response for one source file: pages are 612x792pt rotation-0 at 200 DPI, part
     * names {@code page-<i>}, PNG bytes = the given payload strings (fake bytes — nothing decodes
     * them; they are stored verbatim as blobs).
     */
    static MockResponse renderResponse(String... pngPayloads) {
        String boundary = "docengine-render-fixture";
        StringBuilder pages = new StringBuilder();
        for (int i = 0; i < pngPayloads.length; i++) {
            if (i > 0) {
                pages.append(',');
            }
            pages.append(
                    """
                    {
                      "pageIndex": %d,
                      "widthPt": 612.0,
                      "heightPt": 792.0,
                      "rotation": 0,
                      "dpi": 200,
                      "widthPx": 1700,
                      "heightPx": 2200,
                      "pngPart": "page-%d"
                    }
                    """
                            .formatted(i, i));
        }
        String metadata =
                "{ \"worker\": %s, \"pages\": [%s] }".formatted(RENDER_WORKER_BLOCK, pages);

        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n");
        body.writeUtf8("Content-Type: application/json\r\n\r\n");
        body.writeUtf8(metadata);
        for (int i = 0; i < pngPayloads.length; i++) {
            body.writeUtf8("\r\n--" + boundary + "\r\n");
            body.writeUtf8("Content-Disposition: form-data; name=\"page-" + i + "\"\r\n");
            body.writeUtf8("Content-Type: image/png\r\n\r\n");
            body.write(pngPayloads[i].getBytes(StandardCharsets.UTF_8));
        }
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        return new MockResponse()
                .setHeader("Content-Type", "multipart/mixed; boundary=" + boundary)
                .setBody(body);
    }

    /**
     * A render response whose page metadata is supplied verbatim — for sources whose geometry is
     * NOT the 612x792-at-200-DPI PDF default. An image page's dims and DPI are derived from its
     * pixel count, so a fixture that hard-codes the PDF shape cannot describe one.
     *
     * <p>Deliberately NOT an overload of {@link #renderResponse(String...)}: a two-argument call
     * binds to the fixed-arity method in preference to the varargs one, so every existing
     * {@code renderResponse("PNG-A", "PNG-B")} would have silently started sending the first
     * payload as its metadata JSON. (Observed, as three unrelated pipeline ITs failing
     * WORKER_UNAVAILABLE on an HTTP 200.)
     */
    static MockResponse renderResponseWithMetadata(String pageMetadataJson, String pngPayload) {
        String boundary = "docengine-render-fixture";
        String metadata =
                "{ \"worker\": %s, \"pages\": [%s] }"
                        .formatted(RENDER_WORKER_BLOCK, pageMetadataJson);
        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n");
        body.writeUtf8("Content-Type: application/json\r\n\r\n");
        body.writeUtf8(metadata);
        body.writeUtf8("\r\n--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"page-0\"\r\n");
        body.writeUtf8("Content-Type: image/png\r\n\r\n");
        body.write(pngPayload.getBytes(StandardCharsets.UTF_8));
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        return new MockResponse()
                .setHeader("Content-Type", "multipart/mixed; boundary=" + boundary)
                .setBody(body);
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
