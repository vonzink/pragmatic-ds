package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The test seam a large number of ITs stand on: a test-supplied {@code processingExecutor} must
 * still win, so those tests keep observing the pipeline synchronously on return. Registration
 * order here mirrors the ITs — component scanning registers {@link OrchestrationConfig} first and
 * a {@code @TestConfiguration} lands after it, with bean-definition overriding on.
 */
class ProcessingExecutorSeamTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(OrchestrationConfig.class);

    @Test
    void withoutATestSuppliedBeanTheBoundedPoolIsTheDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasBean("processingExecutor");
                    assertThat(context.getBean("processingExecutor"))
                            .isInstanceOf(ThreadPoolTaskExecutor.class);
                });
    }

    @Test
    void aTestSuppliedSameThreadExecutorStillWins() {
        runner.withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(SameThreadExecutorConfig.class)
                .run(
                        context ->
                                assertThat(context.getBean("processingExecutor"))
                                        .isInstanceOf(SyncTaskExecutor.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class SameThreadExecutorConfig {
        @Bean("processingExecutor")
        TaskExecutor processingExecutor() {
            return new SyncTaskExecutor();
        }
    }
}
