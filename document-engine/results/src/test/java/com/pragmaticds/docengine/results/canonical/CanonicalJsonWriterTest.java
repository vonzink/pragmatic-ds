package com.pragmaticds.docengine.results.canonical;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonWriterTest {

    private final CanonicalJsonWriter writer = new CanonicalJsonWriter();

    @Test
    void writesExactUtf8BytesWithoutBomOrInsignificantWhitespaceAndHashesThoseBytes() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("b", "x");
        input.put("a", 1);

        CanonicalJsonWriter.CanonicalArtifact artifact = writer.write(input);

        assertThat(artifact.bytes()).isEqualTo("{\"a\":1,\"b\":\"x\"}".getBytes(UTF_8));
        assertThat(artifact.bytes()[0]).isNotEqualTo((byte) 0xef);
        assertThat(artifact.sha256())
                .isEqualTo("ecf9e98ec0641e23113ff3ce8bdc78d0ddd249886517fd4a7f68cc83d4e65667");
    }

    @Test
    void ordersNestedObjectKeysByUnicodeCodePointRatherThanUtf16CodeUnit() {
        String supplementary = "\uD800\uDC00";
        String privateUseBmp = "\uE000";
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put(supplementary, 3);
        nested.put("b", 2);
        nested.put(privateUseBmp, 4);
        nested.put("a", 1);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.set("nested", nested);

        assertThat(text(writer.write(input)))
                .isEqualTo("{\"nested\":{\"a\":1,\"b\":2,\"\uE000\":4,\"\uD800\uDC00\":3}}");
    }

    @Test
    void preservesArrayOrderAndWritesJsonPrimitives() {
        ArrayNode input = JsonNodeFactory.instance.arrayNode();
        input.add("last");
        input.add(2);
        input.add(BooleanNode.TRUE);
        input.add(NullNode.instance);

        assertThat(text(writer.write(input))).isEqualTo("[\"last\",2,true,null]");
    }

    @Test
    void writesIntegralAndDecimalNumbersInPlainNormalizedForm() {
        ArrayNode input = JsonNodeFactory.instance.arrayNode();
        input.add(new BigIntegerNode(new BigInteger("123456789012345678901234567890")));
        input.add(new DecimalNode(new BigDecimal("123.45000")));
        input.add(new DecimalNode(new BigDecimal("1E+3")));
        input.add(new DecimalNode(new BigDecimal("0.00000100")));
        input.add(new DecimalNode(new BigDecimal("-0.000")));
        input.add(DoubleNode.valueOf(-0.0d));

        assertThat(text(writer.write(input)))
                .isEqualTo("[123456789012345678901234567890,123.45,1000,0.000001,0,0]");
    }

    @Test
    void preservesComposedAndDecomposedUnicodeAsDifferentBytes() {
        CanonicalJsonWriter.CanonicalArtifact composed = writer.write(TextNode.valueOf("\u00E9"));
        CanonicalJsonWriter.CanonicalArtifact decomposed = writer.write(TextNode.valueOf("e\u0301"));

        assertThat(text(composed)).isEqualTo("\"\u00E9\"");
        assertThat(text(decomposed)).isEqualTo("\"e\u0301\"");
        assertThat(composed.bytes()).isNotEqualTo(decomposed.bytes());
        assertThat(composed.sha256()).isNotEqualTo(decomposed.sha256());
    }

    @Test
    void usesOnlyRequiredShortestJsonStringEscapesForEveryControlCharacter() {
        StringBuilder value = new StringBuilder();
        for (char control = 0; control < 0x20; control++) {
            value.append(control);
        }
        value.append('"').append('\\').append('/');

        assertThat(text(writer.write(TextNode.valueOf(value.toString()))))
                .isEqualTo(
                        "\"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007"
                                + "\\b\\t\\n\\u000b\\f\\r\\u000e\\u000f"
                                + "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017"
                                + "\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f"
                                + "\\\"\\\\/\"");
    }

    @Test
    void strictParseBoundaryRejectsDuplicateObjectKeys() {
        byte[] duplicateKeys = "{\"field\":1,\"field\":2}".getBytes(UTF_8);

        assertThatThrownBy(() -> writer.parseAndWrite(duplicateKeys))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void strictParseBoundaryRejectsTrailingJsonToken() {
        byte[] multipleValues = "{\"field\":1} [2]".getBytes(UTF_8);

        assertThatThrownBy(() -> writer.parseAndWrite(multipleValues))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void strictParseBoundaryCanonicalizesValidInput() {
        byte[] nonCanonical = " { \"b\" : 2, \"a\" : [ 3, 1 ] } \n".getBytes(UTF_8);

        assertThat(text(writer.parseAndWrite(nonCanonical))).isEqualTo("{\"a\":[3,1],\"b\":2}");
    }

    @Test
    void strictParseBoundaryPreservesDecimalPrecisionBeforeCanonicalizing() {
        byte[] preciseNumbers = "[1.234567890123456789,1e3]".getBytes(UTF_8);

        assertThat(text(writer.parseAndWrite(preciseNumbers)))
                .isEqualTo("[1.234567890123456789,1000]");
    }

    @Test
    void rejectsUnpairedSurrogatesInObjectKeys() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("bad\uD800key", 1);

        assertThatThrownBy(() -> writer.write(input))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void rejectsUnpairedSurrogatesInTextValues() {
        TextNode input = TextNode.valueOf("bad\uDC00value");

        assertThatThrownBy(() -> writer.write(input))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> writer.write(DoubleNode.valueOf(Double.NaN)))
                .isInstanceOf(CanonicalizationException.class);
        assertThatThrownBy(() -> writer.write(DoubleNode.valueOf(Double.POSITIVE_INFINITY)))
                .isInstanceOf(CanonicalizationException.class);
        assertThatThrownBy(() -> writer.write(DoubleNode.valueOf(Double.NEGATIVE_INFINITY)))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void rejectsNonJsonNodeTypes() {
        JsonNode[] unsupported = {
            MissingNode.getInstance(),
            new POJONode(Map.of("hidden", "value")),
            BinaryNode.valueOf(new byte[] {1, 2, 3})
        };

        for (JsonNode node : unsupported) {
            assertThatThrownBy(() -> writer.write(node))
                    .as("node type %s", node.getNodeType())
                    .isInstanceOf(CanonicalizationException.class);
        }
    }

    @Test
    void canonicalArtifactDefensivelyCopiesConstructorAndAccessorBytes() {
        byte[] expectedBytes = "{\"a\":1}".getBytes(UTF_8);
        byte[] constructorBytes = expectedBytes.clone();
        String expectedSha =
                "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862";
        CanonicalJsonWriter.CanonicalArtifact artifact =
                new CanonicalJsonWriter.CanonicalArtifact(constructorBytes, expectedSha);

        constructorBytes[0] = '[';
        byte[] accessorBytes = artifact.bytes();
        accessorBytes[1] = 'X';

        assertThat(artifact.bytes()).isEqualTo(expectedBytes);
        assertThat(artifact.sha256()).isEqualTo(expectedSha);
    }

    private static String text(CanonicalJsonWriter.CanonicalArtifact artifact) {
        return new String(artifact.bytes(), UTF_8);
    }
}
