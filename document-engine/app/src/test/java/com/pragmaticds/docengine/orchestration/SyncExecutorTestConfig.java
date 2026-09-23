package com.pragmaticds.docengine.orchestration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Replaces the async processing executor with a same-thread one, so a test that calls
 * {@code createJob}/{@code resume} observes the fully-run pipeline on return — no polling, no
 * sleeps. Requires {@code spring.main.allow-bean-definition-overriding=true} on the importing test.
 */
@TestConfiguration
public class SyncExecutorTestConfig {

    @Bean("processingExecutor")
    public TaskExecutor processingExecutor() {
        return new SyncTaskExecutor();
    }
}
