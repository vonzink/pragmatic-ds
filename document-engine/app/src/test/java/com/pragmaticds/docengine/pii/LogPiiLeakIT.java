package com.pragmaticds.docengine.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * The logging discipline, proven not just intended: ids, counts, codes — never borrower data.
 * A real pipeline-to-extraction plus a correction runs over the known {@code paystub_complete}
 * fixture while a Logback appender captures every {@code com.pragmaticds.docengine} log line, and no
 * captured line may contain a fixture value.
 */
class LogPiiLeakIT extends AbstractExtractionIT {

    /** Known fixture values (see extraction golden). None may appear in any log line. */
    private static final List<String> FIXTURE_PII =
            List.of("Jordan Q. Fixture", "ACME WIDGETS LLC", "$3,565.87", "3565.87");

    @Test
    void no_log_line_from_pipeline_or_correction_contains_borrower_data() throws Exception {
        Logger appLogger = (Logger) LoggerFactory.getLogger("com.pragmaticds.docengine");
        Level previous = appLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        appLogger.setLevel(Level.DEBUG);
        appLogger.addAppender(appender);

        String joined;
        try {
            UUID packageId = insertPackage("log-pii-it");
            insertFixturePages(packageId, "paystub_complete");
            insertFixtureLayout(packageId, "paystub_complete");
            runPipelineToExtraction(packageId);
            UUID documentId = onlyDocumentOf(packageId);

            // A correction exercises the review + audit log paths on top of the pipeline.
            Map<String, Object> netPay = currentFieldsByName(documentId).get("netPay");
            UUID fieldId = (UUID) netPay.get("id");
            mockMvc.perform(
                            patch("/v1/fields/{id}", fieldId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"action\":\"CORRECT\",\"value\":\"1,000.00\"}"))
                    .andExpect(status().isOk());

            joined =
                    List.copyOf(appender.list).stream()
                            .map(LogPiiLeakIT::render)
                            .collect(Collectors.joining("\n"));
        } finally {
            appLogger.detachAppender(appender);
            appLogger.setLevel(previous);
        }

        // The capture is real (guards against a vacuous pass): the extraction stage logs its
        // PII-free counts line, so the appender is genuinely seeing the flow's output.
        assertThat(joined).contains("extracted package=");
        // The discipline: ids, counts, codes — never borrower data.
        for (String pii : FIXTURE_PII) {
            assertThat(joined).doesNotContain(pii);
        }
    }

    /** Full text of an event: message plus any thrown exception chain. */
    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy thrown = event.getThrowableProxy();
                thrown != null;
                thrown = thrown.getCause()) {
            text.append(' ').append(thrown.getClassName()).append(": ").append(thrown.getMessage());
        }
        return text.toString();
    }
}
