package com.pragmaticds.docengine.parsing.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Typed HTTP client for the three worker stage endpoints (docs/WORKER_CONTRACT.md).
 *
 * <p>Requests are {@code multipart/form-data} with a {@code file} part (the bytes) and a
 * {@code request} part (JSON). Responses are JSON except {@code /v1/render}, which is
 * {@code multipart/mixed} (metadata part first, then one binary PNG part per page).
 *
 * <p>Every request carries {@code X-Worker-Secret}. Coordinates in responses are parsed with
 * {@code USE_BIG_DECIMAL_FOR_FLOATS} so the canonical 0.1pt values are carried VERBATIM to
 * persistence — never through a double.
 *
 * <p>Failure taxonomy (all as {@link WorkerCallException}, never body text):
 *
 * <ul>
 *   <li>connect failure → {@code WORKER_UNAVAILABLE}; read/connect timeout → {@code WORKER_TIMEOUT}
 *   <li>non-2xx with a contract envelope → the stable worker code mapped onto {@link ErrorCode}
 *   <li>anything not speaking the contract (unparseable body, missing multipart part) →
 *       {@code WORKER_UNAVAILABLE}
 * </ul>
 */
public class WorkerClient {

    private static final String FILE_PART = "file";
    private static final String REQUEST_PART = "request";
    private static final String SECRET_HEADER = "X-Worker-Secret";
    private static final String METADATA_PART = "metadata";

    private final RestClient restClient;
    private final RestClient ocrRestClient;
    private final ObjectMapper mapper;

    public WorkerClient(
            String baseUrl, String sharedSecret, Duration connectTimeout, Duration readTimeout) {
        this(baseUrl, sharedSecret, connectTimeout, readTimeout, readTimeout);
    }

    public WorkerClient(
            String baseUrl,
            String sharedSecret,
            Duration connectTimeout,
            Duration readTimeout,
            Duration ocrReadTimeout) {
        // SimpleClientHttpRequestFactory: read timeout applies to every socket read (headers AND
        // body), which is the semantics "the worker went quiet mid-response" needs.
        this.restClient = buildRestClient(baseUrl, sharedSecret, connectTimeout, readTimeout);
        this.ocrRestClient =
                buildRestClient(baseUrl, sharedSecret, connectTimeout, ocrReadTimeout);
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        // Exact decimals: the default node factory strips trailing zeros
                        // (36.0 -> 36), which breaks the verbatim rule for values that
                        // round-trip back onto the wire (MIXED uncovered regions -> /v1/ocr).
                        .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                        .build();
    }

    private static RestClient buildRestClient(
            String baseUrl,
            String sharedSecret,
            Duration connectTimeout,
            Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(SECRET_HEADER, sharedSecret)
                .build();
    }

    /** {@code POST /v1/render}: pages of a PDF to PNG. Empty {@code pages} = all pages. */
    public RenderResult render(byte[] pdf, List<Integer> pages, int dpi) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode pageArray = request.putArray("pages");
        pages.forEach(pageArray::add);
        request.put("dpi", dpi);
        return post(
                "/v1/render",
                pdf,
                MediaType.APPLICATION_PDF,
                request.toString(),
                this::parseRender);
    }

    /** One entry of a burst sequence: which attached source (by ordinal), which 0-based page. */
    public record BurstPage(int sourceOrdinal, int pageIndex) {}

    /**
     * {@code POST /v1/burst}: an ordered page selection — possibly spanning several source files —
     * emitted as ONE standalone PDF. Sources are attached as parts {@code file-0..file-N} in list
     * order; the sequence references them by ordinal. All PDF manipulation happens in the worker
     * (page-subset copy for PDFs, original-pixel wrapping for images) — the engine never re-encodes.
     */
    public byte[] burst(List<byte[]> sourceFiles, List<BurstPage> sequence) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode pageArray = request.putArray("pages");
        for (BurstPage page : sequence) {
            ObjectNode entry = pageArray.addObject();
            entry.put("part", "file-" + page.sourceOrdinal());
            entry.put("index", page.pageIndex());
        }

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (int ordinal = 0; ordinal < sourceFiles.size(); ordinal++) {
            String partName = "file-" + ordinal;
            HttpHeaders fileHeaders = new HttpHeaders();
            // The worker sniffs magic bytes, never a declared type — octet-stream is honest for
            // a part that may be a PDF or any accepted image format.
            fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            parts.add(
                    partName,
                    new HttpEntity<>(
                            new ByteArrayResource(sourceFiles.get(ordinal)) {
                                @Override
                                public String getFilename() {
                                    // A constant per ordinal. The original filename is
                                    // PII-adjacent and the worker must never see it
                                    // (contract invariant 4).
                                    return partName;
                                }
                            },
                            fileHeaders));
        }
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        parts.add(REQUEST_PART, new HttpEntity<>(request.toString(), requestHeaders));
        return exchange(restClient, "/v1/burst", parts, this::parseBurst);
    }

    /** {@code POST /v1/text}: native text extraction with word boxes + per-page verdict. */
    public TextResult text(byte[] pdf) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("pages");
        return post("/v1/text", pdf, MediaType.APPLICATION_PDF, request.toString(), this::parseText);
    }

    /** {@code POST /v1/ocr}: OCR of a rendered page PNG (regions restrict for MIXED pages). */
    public OcrResult ocr(byte[] png, OcrRequest ocrRequest) {
        String request;
        try {
            request = mapper.writeValueAsString(ocrRequest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable ocr request", e);
        }
        return post(ocrRestClient, "/v1/ocr", png, MediaType.IMAGE_PNG, request, this::parseOcr);
    }

    /**
     * {@code POST /v1/layout}: layout elements clustered from spans the caller already extracted.
     * The {@code file} part is OPTIONAL — pass the source PDF for files with native pages (rects/
     * lines confirm ruled tables) and {@code null} for pure-scanned files.
     */
    public LayoutResult layout(byte[] pdfOrNull, LayoutRequest layoutRequest) {
        String request;
        try {
            request = mapper.writeValueAsString(layoutRequest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable layout request", e);
        }
        return post(
                "/v1/layout", pdfOrNull, MediaType.APPLICATION_PDF, request, this::parseLayout);
    }

    // ── transport ───────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(byte[] body, MediaType contentType, int status);
    }

    private <T> T post(
            String path,
            byte[] fileBytes,
            MediaType fileType,
            String requestJson,
            ResponseParser<T> parser) {
        return post(restClient, path, fileBytes, fileType, requestJson, parser);
    }

    private <T> T post(
            RestClient client,
            String path,
            byte[] fileBytes,
            MediaType fileType,
            String requestJson,
            ResponseParser<T> parser) {
        // Built by hand rather than MultipartBodyBuilder: that class drags in reactive-streams
        // for async parts this client never uses.
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        // /v1/layout's file part is OPTIONAL (null = pure-scanned source, nothing to confirm
        // rulings against); every other endpoint always passes bytes.
        if (fileBytes != null) {
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(fileType);
            parts.add(
                    FILE_PART,
                    new HttpEntity<>(
                            new ByteArrayResource(fileBytes) {
                                @Override
                                public String getFilename() {
                                    // A constant. The original filename is PII-adjacent and the
                                    // worker must never see it (contract invariant 4).
                                    return FILE_PART;
                                }
                            },
                            fileHeaders));
        }
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        parts.add(REQUEST_PART, new HttpEntity<>(requestJson, requestHeaders));
        return exchange(client, path, parts, parser);
    }

    private <T> T exchange(
            RestClient client,
            String path,
            MultiValueMap<String, Object> parts,
            ResponseParser<T> parser) {
        try {
            return client
                    .post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .exchange(
                            (request, response) -> {
                                byte[] body = response.getBody().readAllBytes();
                                HttpStatusCode status = response.getStatusCode();
                                if (!status.is2xxSuccessful()) {
                                    throw errorFrom(status.value(), body);
                                }
                                return parser.parse(
                                        body, response.getHeaders().getContentType(), status.value());
                            });
        } catch (ResourceAccessException e) {
            throw WorkerCallException.transport(transportCode(e));
        }
    }

    private static ErrorCode transportCode(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) {
                return ErrorCode.WORKER_TIMEOUT;
            }
        }
        return ErrorCode.WORKER_UNAVAILABLE;
    }

    /** Non-2xx: extract the stable code from the contract envelope; NEVER keep body text. */
    private WorkerCallException errorFrom(int status, byte[] body) {
        String workerCode = null;
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.get("error");
            if (error != null && error.isTextual()) {
                workerCode = error.asText();
            }
        } catch (IOException notContractShaped) {
            // Fall through: workerCode stays null.
        }
        if (workerCode == null) {
            return WorkerCallException.http(status, null, ErrorCode.WORKER_UNAVAILABLE);
        }
        return WorkerCallException.http(status, workerCode, mapWorkerCode(workerCode));
    }

    /** Worker stable codes → platform taxonomy. Codes with no local twin collapse conservatively. */
    private static ErrorCode mapWorkerCode(String workerCode) {
        return switch (workerCode) {
            case "CORRUPT_PDF" -> ErrorCode.CORRUPT_PDF;
            case "CORRUPT_IMAGE" -> ErrorCode.CORRUPT_IMAGE;
            case "RENDER_FAILED" -> ErrorCode.RENDER_FAILED;
            case "TEXT_EXTRACTION_FAILED" -> ErrorCode.TEXT_EXTRACTION_FAILED;
            case "OCR_FAILED" -> ErrorCode.OCR_FAILED;
            case "INVALID_REQUEST", "PAGE_OUT_OF_RANGE" -> ErrorCode.INVALID_REQUEST;
            // UNAUTHORIZED here means the shared secret is misconfigured — an ops problem.
            case "UNAUTHORIZED", "INTERNAL" -> ErrorCode.INTERNAL;
            default -> ErrorCode.WORKER_UNAVAILABLE;
        };
    }

    // ── response parsing ────────────────────────────────────────────────────

    private JsonNode readTree(byte[] body, int status) {
        try {
            return mapper.readTree(body);
        } catch (IOException notContractShaped) {
            throw WorkerCallException.malformed(status);
        }
    }

    /**
     * The multipart/mixed boundary, unquoted. RFC 2045 allows {@code boundary="..."} and the real
     * worker (Starlette) quotes it; {@code MediaType.getParameter} returns the quotes verbatim.
     * The first end-to-end run failed exactly here while every unquoted-boundary test fixture
     * passed.
     */
    private static String mixedBoundary(MediaType contentType, int status) {
        String boundary = contentType == null ? null : contentType.getParameter("boundary");
        if (boundary == null) {
            throw WorkerCallException.malformed(status);
        }
        if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        return boundary;
    }

    private byte[] parseBurst(byte[] body, MediaType contentType, int status) {
        Map<String, MultipartMixed.Part> parts =
                MultipartMixed.parse(body, mixedBoundary(contentType, status));
        MultipartMixed.Part metadata = parts.get(METADATA_PART);
        if (metadata == null) {
            throw WorkerCallException.malformed(status);
        }
        JsonNode root = readTree(metadata.content(), status);
        String pdfPart = root.path("pdfPart").asText();
        MultipartMixed.Part pdf = pdfPart.isEmpty() ? null : parts.get(pdfPart);
        if (pdf == null) {
            throw WorkerCallException.malformed(status);
        }
        return pdf.content();
    }

    private RenderResult parseRender(byte[] body, MediaType contentType, int status) {
        Map<String, MultipartMixed.Part> parts =
                MultipartMixed.parse(body, mixedBoundary(contentType, status));
        MultipartMixed.Part metadata = parts.get(METADATA_PART);
        if (metadata == null) {
            throw WorkerCallException.malformed(status);
        }
        JsonNode root = readTree(metadata.content(), status);
        List<RenderedPage> pages = new ArrayList<>();
        for (JsonNode pageNode : root.path("pages")) {
            RenderPageMeta meta =
                    new RenderPageMeta(
                            pageNode.path("pageIndex").asInt(),
                            decimal(pageNode.get("widthPt")),
                            decimal(pageNode.get("heightPt")),
                            pageNode.path("rotation").asInt(),
                            pageNode.path("dpi").asInt(),
                            pageNode.path("widthPx").asInt(),
                            pageNode.path("heightPx").asInt(),
                            pageNode.path("pngPart").asText());
            MultipartMixed.Part pngPart = parts.get(meta.pngPart());
            if (pngPart == null) {
                throw WorkerCallException.malformed(status);
            }
            pages.add(new RenderedPage(meta, pngPart.content()));
        }
        return new RenderResult(parseWorker(root.get("worker")), pages);
    }

    private TextResult parseText(byte[] body, MediaType contentType, int status) {
        String json = new String(body, StandardCharsets.UTF_8);
        JsonNode root = readTree(body, status);
        List<TextPage> pages = new ArrayList<>();
        for (JsonNode pageNode : root.path("pages")) {
            List<NativeSpan> spans = new ArrayList<>();
            for (JsonNode spanNode : pageNode.path("spans")) {
                spans.add(
                        new NativeSpan(
                                spanNode.path("ordinal").asInt(),
                                spanNode.path("text").asText(),
                                decimal(spanNode.get("x")),
                                decimal(spanNode.get("y")),
                                decimal(spanNode.get("width")),
                                decimal(spanNode.get("height")),
                                decimal(spanNode.get("fontSize")),
                                textOrNull(spanNode.get("fontName"))));
            }
            pages.add(
                    new TextPage(
                            pageNode.path("pageIndex").asInt(),
                            decimal(pageNode.get("widthPt")),
                            decimal(pageNode.get("heightPt")),
                            pageNode.path("rotation").asInt(),
                            pageNode.path("verdict").asText(),
                            spans,
                            boxesOrNull(pageNode.get("uncoveredRegions")),
                            decimal(pageNode.get("inkFraction"))));
        }
        return new TextResult(parseWorker(root.get("worker")), pages, json);
    }

    private LayoutResult parseLayout(byte[] body, MediaType contentType, int status) {
        String json = new String(body, StandardCharsets.UTF_8);
        JsonNode root = readTree(body, status);
        List<LayoutPage> pages = new ArrayList<>();
        for (JsonNode pageNode : root.path("pages")) {
            List<LayoutWireElement> elements = new ArrayList<>();
            for (JsonNode el : pageNode.path("elements")) {
                List<Integer> spanOrdinals = new ArrayList<>();
                for (JsonNode ordinal : el.path("spanOrdinals")) {
                    spanOrdinals.add(ordinal.asInt());
                }
                elements.add(
                        new LayoutWireElement(
                                textOrNull(el.get("elementId")),
                                textOrNull(el.get("parentElementId")),
                                el.path("elementType").asText(),
                                el.path("ordinal").asInt(),
                                decimal(el.get("x")),
                                decimal(el.get("y")),
                                decimal(el.get("width")),
                                decimal(el.get("height")),
                                decimal(el.get("confidence")),
                                textOrNull(el.get("detector")),
                                textOrNull(el.get("detectorVersion")),
                                subtreeOrNull(el.get("attributes")),
                                spanOrdinals));
            }
            // Absent stays distinguishable from declared-empty: the contract requires the key,
            // but a build that omits it must surface as null ("never declared"), never as [] —
            // an invented [] would claim full pixel coverage nobody declared.
            JsonNode declaredNode = pageNode.get("notImplemented");
            List<String> notImplemented;
            if (declaredNode == null || declaredNode.isNull()) {
                notImplemented = null;
            } else {
                notImplemented = new ArrayList<>();
                for (JsonNode type : declaredNode) {
                    notImplemented.add(type.asText());
                }
            }
            pages.add(
                    new LayoutPage(pageNode.path("pageIndex").asInt(), elements, notImplemented));
        }
        return new LayoutResult(parseWorker(root.get("worker")), pages, json);
    }

    private OcrResult parseOcr(byte[] body, MediaType contentType, int status) {
        String json = new String(body, StandardCharsets.UTF_8);
        JsonNode root = readTree(body, status);
        List<OcrSpan> spans = new ArrayList<>();
        for (JsonNode spanNode : root.path("spans")) {
            spans.add(
                    new OcrSpan(
                            spanNode.path("ordinal").asInt(),
                            spanNode.path("text").asText(),
                            decimal(spanNode.get("x")),
                            decimal(spanNode.get("y")),
                            decimal(spanNode.get("width")),
                            decimal(spanNode.get("height")),
                            textOrNull(spanNode.get("engine")),
                            decimal(spanNode.get("confidence"))));
        }
        JsonNode raw = root.path("raw");
        return new OcrResult(
                parseWorker(root.get("worker")),
                root.path("pageIndex").asInt(),
                intOrNull(root.get("detectedRotation")),
                decimal(root.get("osdConfidence")),
                textOrNull(root.get("engine")),
                textOrNull(root.get("fallbackReason")),
                decimal(root.get("confidenceMedian")),
                spans,
                subtreeOrNull(raw.get("rapidocr")),
                subtreeOrNull(raw.get("tesseract")),
                json);
    }

    private static WorkerBlock parseWorker(JsonNode workerNode) {
        if (workerNode == null || workerNode.isNull()) {
            return new WorkerBlock(null, Map.of());
        }
        Map<String, String> libraries = new LinkedHashMap<>();
        JsonNode libNode = workerNode.path("libraries");
        libNode.fieldNames()
                .forEachRemaining(
                        name -> {
                            JsonNode v = libNode.get(name);
                            libraries.put(name, v == null || v.isNull() ? null : v.asText());
                        });
        return new WorkerBlock(textOrNull(workerNode.get("version")), libraries);
    }

    private static List<WireBox> boxesOrNull(JsonNode arrayNode) {
        if (arrayNode == null || arrayNode.isNull()) {
            return null;
        }
        List<WireBox> boxes = new ArrayList<>();
        for (JsonNode box : arrayNode) {
            boxes.add(
                    new WireBox(
                            decimal(box.get("x")),
                            decimal(box.get("y")),
                            decimal(box.get("width")),
                            decimal(box.get("height"))));
        }
        return boxes;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** A raw engine subtree re-serialized for parser_output; null when the engine did not run. */
    private static String subtreeOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
