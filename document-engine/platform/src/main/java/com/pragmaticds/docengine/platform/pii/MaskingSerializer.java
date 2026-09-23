package com.pragmaticds.docengine.platform.pii;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * The masking boundary. Registered globally (see {@code PiiJacksonConfig}) so every {@link
 * MaskableValue} on any response — extraction fields, package export, review history — passes
 * through here on its way to JSON:
 *
 * <ul>
 *   <li>a null value serialises as JSON null (nothing to hide);
 *   <li>a SENSITIVE value serialises as a masked plain string ({@link MaskingService#mask});
 *   <li>a non-sensitive value delegates to Jackson's default serialisation, preserving its natural
 *       JSON type — a number stays a number, a string a string — so the wire shape is unchanged for
 *       every field that is not sensitive.
 * </ul>
 */
public class MaskingSerializer extends JsonSerializer<MaskableValue> {

    @Override
    public void serialize(MaskableValue value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        Object raw = value.raw();
        if (raw == null) {
            gen.writeNull();
            return;
        }
        if (value.sensitive()) {
            gen.writeString(MaskingService.mask(String.valueOf(raw)));
            return;
        }
        serializers.defaultSerializeValue(raw, gen);
    }
}
