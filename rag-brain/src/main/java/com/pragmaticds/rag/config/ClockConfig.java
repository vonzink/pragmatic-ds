package com.pragmaticds.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * System UTC clock as an injectable bean, so services that need "today" (e.g.
 * {@link com.pragmaticds.rag.service.cost.SpendGuardService}'s daily spend cap)
 * can be unit-tested against a fixed instant instead of real wall-clock time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
