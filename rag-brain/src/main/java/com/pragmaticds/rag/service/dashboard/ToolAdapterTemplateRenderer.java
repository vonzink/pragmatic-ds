package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ToolAdapterTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    public URI renderUrl(String template, DashboardToolCallRequest request) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("urlTemplate is required");
        }
        return URI.create(renderString(template, request, true));
    }

    public Object renderJson(Object template, DashboardToolCallRequest request) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rendered.put(String.valueOf(entry.getKey()), renderJson(entry.getValue(), request));
            }
            return rendered;
        }
        if (template instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderJson(item, request));
            }
            return rendered;
        }
        if (template instanceof String value) {
            return renderJsonString(value, request);
        }
        return template;
    }

    private Object renderJsonString(String template, DashboardToolCallRequest request) {
        Matcher exact = PLACEHOLDER.matcher(template);
        if (exact.matches()) {
            return valueFor(exact.group(1), request);
        }
        return renderString(template, request, false);
    }

    private String renderString(String template, DashboardToolCallRequest request, boolean encodeUrlValues) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = valueFor(matcher.group(1), request);
            String replacement = String.valueOf(value);
            if (encodeUrlValues) {
                replacement = URLEncoder.encode(replacement, StandardCharsets.UTF_8);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Object valueFor(String name, DashboardToolCallRequest request) {
        if ("user.userId".equals(name)) {
            return requiredUser(request).userId();
        }
        if ("user.tenantId".equals(name)) {
            return requiredUser(request).tenantId();
        }
        Map<String, Object> arguments = request == null || request.arguments() == null
                ? Map.of()
                : request.arguments();
        Object value = arguments.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing tool adapter placeholder: " + name);
        }
        return value;
    }

    private UserContext requiredUser(DashboardToolCallRequest request) {
        if (request == null || request.user() == null) {
            throw new IllegalArgumentException("Missing tool adapter placeholder: user");
        }
        return request.user();
    }
}
