package com.pragmaticds.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduler so {@code @Scheduled} jobs run — currently the opt-in
 * ops-data retention job and the adaptive-retrieval learning aggregation job
 * ({@code SourceWeightLearningService}). Each job is individually gated by its own
 * config, so enabling scheduling here has no effect until a job opts in.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
