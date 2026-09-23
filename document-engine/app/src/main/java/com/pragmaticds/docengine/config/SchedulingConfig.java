package com.pragmaticds.docengine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler so {@code @Scheduled} beans (the retention purge sweep) actually fire.
 * Separate from the app class so the reason it exists is visible: the ONLY scheduled work is the
 * hourly retention purge. Its cadence is {@code docengine.retention.purge-cron} (set to {@code -} to
 * disable — tests do exactly that and drive the sweep synchronously instead).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
