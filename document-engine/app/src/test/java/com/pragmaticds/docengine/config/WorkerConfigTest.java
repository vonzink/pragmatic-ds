package com.pragmaticds.docengine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pragmaticds.docengine.parsing.client.OcrRequest;
import com.pragmaticds.docengine.parsing.client.OcrResult;
import com.pragmaticds.docengine.parsing.client.WorkerCallException;
import com.pragmaticds.docengine.parsing.client.WorkerClient;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerConfigTest {

    private static final byte[] PDF = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private static final String OCR_RESPONSE =
            """
            {
              "worker": {"version": "test", "libraries": {}},
              "pageIndex": 0,
              "detectedRotation": 0,
              "osdConfidence": 1.0,
              "engine": "RAPIDOCR",
              "fallbackReason": null,
              "confidenceMedian": 1.0,
              "spans": [],
              "gates": {},
              "raw": {"rapidocr": null, "tesseract": null}
            }
            """;

    private static final String TEXT_RESPONSE =
            """
            {
              "worker": {"version": "test", "libraries": {}},
              "pages": []
            }
            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void ocr_timeout_applies_only_to_ocr_requests() {
        server.enqueue(json(OCR_RESPONSE).setBodyDelay(1500, TimeUnit.MILLISECONDS));
        server.enqueue(json(TEXT_RESPONSE).setBodyDelay(1500, TimeUnit.MILLISECONDS));

        new ApplicationContextRunner()
                .withUserConfiguration(WorkerConfig.class)
                .withPropertyValues(
                        "docengine.processing.adapter=worker",
                        "docengine.worker.base-url=" + server.url("/"),
                        "docengine.worker.shared-secret=test-worker-secret",
                        "docengine.worker.connect-timeout-seconds=1",
                        "docengine.worker.timeout-seconds=1",
                        "docengine.worker.ocr-timeout-seconds=2")
                .run(
                        context -> {
                            WorkerClient client = context.getBean(WorkerClient.class);

                            OcrResult ocr =
                                    client.ocr(
                                            PNG,
                                            new OcrRequest(
                                                    0,
                                                    new BigDecimal("612.0"),
                                                    new BigDecimal("792.0"),
                                                    200,
                                                    0,
                                                    List.of()));
                            assertThat(ocr.engine()).isEqualTo("RAPIDOCR");

                            WorkerCallException timeout =
                                    catchThrowableOfType(
                                            WorkerCallException.class, () -> client.text(PDF));
                            assertThat(timeout.errorCode()).isEqualTo(ErrorCode.WORKER_TIMEOUT);
                        });
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
