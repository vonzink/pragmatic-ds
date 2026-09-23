package com.pragmaticds.rag.lab.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor instance runs execute on — its own bean, deliberately.
 *
 * <p>Autowiring a bare {@code TaskExecutor} into the poller was a latent boot failure: the
 * application context holds more than one ({@code applicationTaskExecutor} plus the scheduling
 * pool, which is itself a {@code TaskExecutor}), so the first deployment to enable execution
 * died in bean wiring — the exact class of misconfiguration Phase 7's end-to-end suite exists
 * to catch before a rollout does.
 *
 * <p>Bounded by the deployment's own concurrency limit. The pool is not what enforces the
 * limits — the dispatcher's four scopes are — but a pool wider than the deployment limit would
 * just hold idle threads, and a queue longer than it would hide a stuck dispatcher.
 */
@Configuration
@ConditionalOnProperty(prefix = "ragbrain.instances.execution", name = "enabled",
        havingValue = "true")
class InstanceExecutionConfiguration {

    @Bean(name = "instanceRunExecutor")
    ThreadPoolTaskExecutor instanceRunExecutor(InstanceExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int bound = Math.max(1, properties.maxConcurrentRuns());
        executor.setCorePoolSize(bound);
        executor.setMaxPoolSize(bound);
        executor.setQueueCapacity(bound);
        executor.setThreadNamePrefix("instance-run-");
        return executor;
    }
}
