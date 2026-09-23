package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Generalized instance manifest decoding is independently feature-gated from legacy Income Lab. */
@Configuration
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class InstanceManifestCodecConfiguration {

    @Bean
    InstanceManifestCodec instanceManifestCodec() {
        return InstanceManifestCodec.strict(new LabManifestWriter());
    }
}
