package com.pragmaticds.rag.service.dashboard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolAdapterSecretRedactor {

    public Object redact(Object value, List<String> secrets) {
        List<String> usableSecrets = secrets == null ? List.of() : secrets.stream()
                .filter(secret -> secret != null && !secret.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (usableSecrets.isEmpty()) {
            return value;
        }
        return redactValue(value, usableSecrets);
    }

    private Object redactValue(Object value, List<String> secrets) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(redactText(String.valueOf(entry.getKey()), secrets), redactValue(entry.getValue(), secrets));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(redactValue(item, secrets));
            }
            return out;
        }
        if (value instanceof String text) {
            return redactText(text, secrets);
        }
        return value;
    }

    private String redactText(String text, List<String> secrets) {
        String redacted = text;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, "[redacted]");
        }
        return redacted;
    }
}
