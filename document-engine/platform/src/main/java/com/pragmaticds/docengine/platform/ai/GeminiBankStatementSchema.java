package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the canonical JSON Schema into Gemini's supported OpenAPI schema subset. */
final class GeminiBankStatementSchema {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    Schema fromCanonical(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("canonical schema must be an object");
            }
            return convert(root, root);
        } catch (RuntimeException invalidSchema) {
            throw invalidSchema;
        } catch (Exception invalidSchema) {
            throw new IllegalArgumentException("canonical schema is invalid", invalidSchema);
        }
    }

    private Schema convert(JsonNode node, JsonNode root) {
        JsonNode resolved = resolve(node, root);
        TypeAndNullability type = type(resolved.path("type"));
        Schema.Builder builder = Schema.builder().type(type.type());
        if (type.nullable()) {
            builder.nullable(true);
        }
        if (resolved.path("description").isTextual()) {
            builder.description(resolved.path("description").asText());
        }
        if (resolved.path("minimum").isNumber()) {
            builder.minimum(resolved.path("minimum").asDouble());
        }
        if (resolved.path("enum").isArray()) {
            builder.enum_(texts(resolved.path("enum")));
        }
        if (type.type() == Type.Known.ARRAY) {
            JsonNode items = resolved.path("items");
            if (items.isMissingNode()) {
                throw new IllegalArgumentException("array schema requires items");
            }
            builder.items(convert(items, root));
        }
        if (type.type() == Type.Known.OBJECT) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            resolved.path("properties")
                    .properties()
                    .forEach(
                            field -> properties.put(field.getKey(), convert(field.getValue(), root)));
            builder.properties(properties);
            if (!properties.isEmpty()) {
                builder.propertyOrdering(new ArrayList<>(properties.keySet()));
            }
            if (resolved.path("required").isArray()) {
                builder.required(texts(resolved.path("required")));
            }
        }
        return builder.build();
    }

    private static JsonNode resolve(JsonNode node, JsonNode root) {
        JsonNode resolved = node;
        int depth = 0;
        while (resolved.path("$ref").isTextual()) {
            String reference = resolved.path("$ref").asText();
            if (!reference.startsWith("#/")) {
                throw new IllegalArgumentException("only local schema references are supported");
            }
            resolved = root.at(reference.substring(1));
            if (resolved.isMissingNode() || ++depth > 20) {
                throw new IllegalArgumentException("unresolvable schema reference");
            }
        }
        return resolved;
    }

    private static TypeAndNullability type(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return new TypeAndNullability(knownType(typeNode.asText()), false);
        }
        if (typeNode.isArray()) {
            String concrete = null;
            boolean nullable = false;
            for (JsonNode candidate : typeNode) {
                if ("null".equals(candidate.asText())) {
                    nullable = true;
                } else if (concrete == null) {
                    concrete = candidate.asText();
                } else {
                    throw new IllegalArgumentException("multiple concrete schema types unsupported");
                }
            }
            if (concrete != null) {
                return new TypeAndNullability(knownType(concrete), nullable);
            }
        }
        throw new IllegalArgumentException("schema type is required");
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Type.Known knownType(String value) {
        return switch (value) {
            case "string" -> Type.Known.STRING;
            case "integer" -> Type.Known.INTEGER;
            case "number" -> Type.Known.NUMBER;
            case "boolean" -> Type.Known.BOOLEAN;
            case "array" -> Type.Known.ARRAY;
            case "object" -> Type.Known.OBJECT;
            default -> throw new IllegalArgumentException("unsupported schema type");
        };
    }

    private record TypeAndNullability(Type.Known type, boolean nullable) {}
}
