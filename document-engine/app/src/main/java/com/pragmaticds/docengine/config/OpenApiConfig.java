package com.pragmaticds.docengine.config;

import com.pragmaticds.docengine.platform.pii.MaskableValue;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI generation tweaks.
 *
 * <p>{@link MaskableValue} is an internal wrapper: on the wire it serialises as the value ITSELF —
 * a scalar (string/number/ISO-date), masked to a string when the field is sensitive — never as its
 * {@code {raw, sensitive}} object shape. Left alone, springdoc reflects the Java fields and
 * advertises that object, which is a lie a generated client would believe. Mapping it to
 * {@code Object} makes springdoc emit an unconstrained (any-scalar) schema, which is the truthful
 * representation of a polymorphic masked-or-raw value.
 */
@Configuration
public class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().replaceWithClass(MaskableValue.class, Object.class);
    }
}
