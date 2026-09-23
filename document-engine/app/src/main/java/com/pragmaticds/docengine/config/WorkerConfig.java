package com.pragmaticds.docengine.config;

import com.pragmaticds.docengine.parsing.client.WorkerClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the typed worker HTTP client from {@code docengine.worker.*} (application.yml): base-url,
 * shared-secret, generic timeout-seconds, and OCR timeout-seconds. Active only under {@code
 * docengine.processing.adapter=worker} — the stub profile must not require a worker URL to boot.
 *
 * <p>{@code connect-timeout-seconds} has no yml entry on purpose: 5s is a sane default and the
 * property remains overridable without another config knob to document.
 */
@Configuration
@ConditionalOnProperty(name = "docengine.processing.adapter", havingValue = "worker")
public class WorkerConfig {

    @Bean
    public WorkerClient workerClient(
            @Value("${docengine.worker.base-url}") String baseUrl,
            @Value("${docengine.worker.shared-secret}") String sharedSecret,
            @Value("${docengine.worker.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${docengine.worker.timeout-seconds:120}") long timeoutSeconds,
            @Value(
                            "${docengine.worker.ocr-timeout-seconds:${docengine.worker.timeout-seconds:300}}")
                    long ocrTimeoutSeconds) {
        return new WorkerClient(
                baseUrl,
                sharedSecret,
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(timeoutSeconds),
                Duration.ofSeconds(ocrTimeoutSeconds));
    }
}
