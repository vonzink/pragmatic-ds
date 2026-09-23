package com.pragmaticds.rag.lab.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceManifestCodecTest {

    private final InstanceManifestCodec codec = InstanceManifestCodec.strict(new LabManifestWriter());

    @Test
    void encodesV2AsCanonicalSortedJsonWithStableLowercaseDigest() {
        InstanceReleaseManifest manifest = manifest();

        EncodedManifest encoded = codec.encode(manifest);
        String json = new String(new LabManifestWriter().canonicalize(encoded.json()), StandardCharsets.UTF_8);

        assertEquals("{\"behavior\":{\"retrievalQuery\":\"income eligibility\",\"systemPrompt\":\"system\","
                        + "\"taskPrompt\":\"task\",\"temperature\":0.250},\"corpus\":{\"collections\":[{\"collectionId\":\"123e4567-e89b-12d3-a456-426614174000\",\"collectionVersion\":7}]},"
                        + "\"evaluations\":{\"minimumScore\":0.950,\"scenarioSetId\":\"income-golden\",\"scenarioSetVersion\":3},"
                        + "\"limits\":{\"maximumConcurrentRuns\":2,\"maximumDiscussionTokens\":4000,\"maximumExpectedCostUsd\":1.50,\"maximumInputTokens\":12000,\"maximumOutputTokens\":800,\"maximumRetrievedTokens\":3000},"
                        + "\"manifestVersion\":2,\"model\":{\"fallbackPolicy\":\"NONE\",\"model\":\"gpt-test\",\"provider\":\"openai\"},"
                        + "\"output\":{\"schemaId\":\"income-output-v1\",\"schemaSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
                        + "\"parsedData\":{\"allowedDocumentTypes\":[\"PAYSTUB\",\"W2\"],\"canonicalizationVersion\":\"DOCENGINE-C14N-1\",\"envelopeVersion\":\"1.0.0\",\"minimumSupportedDocuments\":1,\"missingFields\":\"PRESERVE\",\"requireAnyDocumentTypes\":[\"PAYSTUB\",\"W2\"],\"reviewRequired\":\"WARN\"},"
                        + "\"tools\":[{\"inputSchemaSha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"name\":\"income.calculate\",\"outputSchemaSha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"version\":\"1\"}]}"
                , json);
        assertTrue(encoded.sha256().matches("[0-9a-f]{64}"));
        assertEquals(encoded.sha256(), codec.encode(manifest).sha256());
    }

    @Test
    void preservesContractArrayOrderWhenCanonicalizing() {
        InstanceReleaseManifest forward = withCollections(List.of(collection(7), collection(8)));
        InstanceReleaseManifest reversed = withCollections(List.of(collection(8), collection(7)));

        assertFalse(codec.encode(forward).sha256().equals(codec.encode(reversed).sha256()));
        assertEquals("123e4567-e89b-12d3-a456-426614174000",
                ((Map<?, ?>) ((List<?>) ((Map<?, ?>) codec.encode(forward).json().get("corpus"))
                        .get("collections")).getFirst()).get("collectionId"));
    }

    @Test
    void decodingCanonicalBytesRejectsDuplicateMembersTrailingTokensAndNonFiniteNumbers() {
        assertManifestFailure("{\"manifestVersion\":2,\"manifestVersion\":2}".getBytes(StandardCharsets.UTF_8),
                LabManifestWriter.ManifestException.Code.MANIFEST_DUPLICATE_MEMBER);
        assertManifestFailure("{\"manifestVersion\":2} {\"extra\":true}".getBytes(StandardCharsets.UTF_8),
                LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON);
        assertManifestFailure("{\"manifestVersion\":NaN}".getBytes(StandardCharsets.UTF_8),
                LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON);
    }

    @Test
    void roundTripsEveryPinnedV2ContractWithExactBigDecimalScale() {
        InstanceReleaseManifest expected = manifest();
        EncodedManifest encoded = codec.encode(expected);

        DecodedInstanceManifest.V2 decoded = assertInstanceOf(DecodedInstanceManifest.V2.class,
                codec.decode(encoded.json()));

        assertEquals(expected, decoded.manifest());
        assertEquals(new BigDecimal("0.250"), decoded.manifest().behavior().temperature());
        assertEquals(new BigDecimal("1.50"), decoded.manifest().limits().maximumExpectedCostUsd());
        assertEquals(new BigDecimal("0.950"), decoded.manifest().evaluations().minimumScore());
    }

    @Test
    void encodedManifestAndTypedContractsDefensivelyCopyCollections() {
        List<InstanceReleaseManifest.CollectionRef> collections = new ArrayList<>(List.of(collection(7)));
        Set<String> allowed = new java.util.LinkedHashSet<>(Set.of("PAYSTUB"));
        List<InstanceReleaseManifest.ToolContract> tools = new ArrayList<>(List.of(tool()));
        InstanceReleaseManifest manifest = manifestWith(collections, allowed, tools);
        EncodedManifest encoded = codec.encode(manifest);

        collections.clear();
        allowed.clear();
        tools.clear();

        assertEquals(1, manifest.corpus().collections().size());
        assertEquals(Set.of("PAYSTUB"), manifest.parsedData().allowedDocumentTypes());
        assertEquals(1, manifest.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> encoded.json().put("changed", true));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) encoded.json().get("behavior")).put("taskPrompt", "changed"));
    }

    @Test
    void rejectsUnknownManifestVersionsRatherThanTreatingThemAsV2() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(Map.of("manifestVersion", 3)));
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceReleaseManifest(1, manifest().parsedData(), manifest().model(),
                        manifest().corpus(), manifest().behavior(), manifest().tools(), manifest().output(),
                        manifest().limits(), manifest().evaluations()));
    }

    @Test
    void rejectsDecimalAndExponentV2VersionSpellingsFromBytesAndMaps() {
        EncodedManifest encoded = codec.encode(manifest());
        String canonical = new String(new LabManifestWriter().canonicalize(encoded.json()), StandardCharsets.UTF_8);

        for (String invalidVersion : List.of("2.0", "2.00", "2e0")) {
            byte[] bytes = canonical.replace("\"manifestVersion\":2,",
                    "\"manifestVersion\":" + invalidVersion + ",").getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes), invalidVersion);

            Map<String, Object> stored = new LinkedHashMap<>(encoded.json());
            stored.put("manifestVersion", new BigDecimal(invalidVersion));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(stored), invalidVersion);
        }
    }

    @Test
    void rejectsLossyGenericJacksonMapsWhileTheBytePathKeepsCanonicalHashIdentity() throws Exception {
        EncodedManifest encoded = codec.encode(manifest());
        byte[] canonical = new LabManifestWriter().canonicalize(encoded.json());
        @SuppressWarnings("unchecked")
        Map<String, Object> genericJacksonMap = new ObjectMapper().readValue(canonical, Map.class);

        assertInstanceOf(Double.class, ((Map<?, ?>) genericJacksonMap.get("behavior")).get("temperature"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(genericJacksonMap));

        DecodedInstanceManifest.V2 exact = assertInstanceOf(DecodedInstanceManifest.V2.class,
                codec.decode(canonical));
        assertEquals(encoded.sha256(), codec.encode(exact.manifest()).sha256());
    }

    private void assertManifestFailure(byte[] bytes, LabManifestWriter.ManifestException.Code expected) {
        LabManifestWriter.ManifestException failure = assertThrows(LabManifestWriter.ManifestException.class,
                () -> codec.decode(bytes));
        assertEquals(expected, failure.code());
    }

    private InstanceReleaseManifest manifest() {
        return manifestWith(new ArrayList<>(List.of(collection(7))),
                new java.util.LinkedHashSet<>(Set.of("PAYSTUB", "W2")), new ArrayList<>(List.of(tool())));
    }

    private InstanceReleaseManifest withCollections(List<InstanceReleaseManifest.CollectionRef> collections) {
        return new InstanceReleaseManifest(2, manifest().parsedData(), manifest().model(),
                new InstanceReleaseManifest.CorpusContract(collections), manifest().behavior(), manifest().tools(),
                manifest().output(), manifest().limits(), manifest().evaluations());
    }

    private InstanceReleaseManifest manifestWith(List<InstanceReleaseManifest.CollectionRef> collections,
                                                 Set<String> allowed,
                                                 List<InstanceReleaseManifest.ToolContract> tools) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1", allowed,
                        new java.util.LinkedHashSet<>(Set.of("PAYSTUB", "W2")), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("openai", "gpt-test",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(collections),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "income eligibility",
                        new BigDecimal("0.250")),
                tools,
                new InstanceReleaseManifest.OutputContract("income-output-v1", digest('a')),
                new InstanceReleaseManifest.LimitContract(12000, 3000, 800, 4000, 2,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-golden", 3,
                        new BigDecimal("0.950")));
    }

    private InstanceReleaseManifest.CollectionRef collection(long version) {
        return new InstanceReleaseManifest.CollectionRef(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), version);
    }

    private InstanceReleaseManifest.ToolContract tool() {
        return new InstanceReleaseManifest.ToolContract("income.calculate", "1", digest('b'), digest('c'));
    }

    private String digest(char digit) {
        return String.valueOf(digit).repeat(64);
    }
}
