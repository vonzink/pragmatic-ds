package com.pragmaticds.docengine.platform.pii;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MaskingSerializer} on the application's Jackson {@code ObjectMapper} for every
 * HTTP response. Spring Boot's Jackson auto-configuration folds any {@link Module} bean into the
 * mapper the web message converters use, so masking is a property of the serializer layer — not of
 * any one controller.
 */
@Configuration
public class PiiJacksonConfig {

    @Bean
    public Module piiMaskingModule() {
        SimpleModule module = new SimpleModule("pii-masking");
        module.addSerializer(MaskableValue.class, new MaskingSerializer());
        return module;
    }
}
