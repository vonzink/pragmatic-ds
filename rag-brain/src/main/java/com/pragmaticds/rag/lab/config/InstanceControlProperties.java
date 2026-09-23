package com.pragmaticds.rag.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment switch for the generalized instance registry HTTP and service surface. */
@ConfigurationProperties(prefix = "ragbrain.instances")
public record InstanceControlProperties(boolean enabled) {}
