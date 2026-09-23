package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.ModelCatalogException;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogVersion;
import com.pragmaticds.rag.lab.run.repository.LabModelCatalogEntryRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelCatalogVersionRepository;
import com.pragmaticds.rag.provider.AiModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the deployment may offer, and what it refuses to make up.
 *
 * <p>Two properties carry most of the weight. A model whose provider has no credentials is not
 * offerable, because offering it would advertise something nothing can call. And a price list that
 * changes appends a version rather than overwriting one, because a run priced last month must stay
 * re-derivable from the numbers it was actually priced against.
 */
class InstanceModelCatalogServiceTest {

    private LabModelCatalogVersionRepository versions;
    private LabModelCatalogEntryRepository entries;
    private List<LabModelCatalogVersion> stored;

    @BeforeEach
    void setUp() {
        versions = mock(LabModelCatalogVersionRepository.class);
        entries = mock(LabModelCatalogEntryRepository.class);
        stored = new ArrayList<>();
        when(versions.findByCatalogSha256(anyString())).thenAnswer(call -> stored.stream()
                .filter(v -> v.getCatalogSha256().equals(call.getArgument(0)))
                .findFirst());
        when(versions.saveAndFlush(any())).thenAnswer(call -> {
            LabModelCatalogVersion version = call.getArgument(0);
            ReflectionTestUtils.setField(version, "id", UUID.randomUUID());
            stored.add(version);
            return version;
        });
    }

    @Test
    void onlyModelsWhoseProviderHasCredentialsAreOfferable() {
        InstanceModelCatalogService catalog = service(
                List.of(entry("anthropic", "claude-opus-5"), entry("openai", "gpt-decimal")),
                "anthropic");

        List<InstanceModelCatalogService.CatalogModel> available = catalog.available();

        assertEquals(1, available.size(),
                "a model whose provider has no bean has no credentials and cannot be called");
        assertEquals("claude-opus-5", available.getFirst().model());
    }

    @Test
    void aCatalogWhoseProvidersAllLackCredentialsFailsRatherThanOfferingNothingQuietly() {
        InstanceModelCatalogService catalog = service(
                List.of(entry("anthropic", "claude-opus-5")), "openai");

        ModelCatalogException refused =
                assertThrows(ModelCatalogException.class, catalog::available);

        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_UNAVAILABLE, refused.code());
        // An empty list would let a wizard render zero options and look merely unconfigured.
        verify(versions, never()).saveAndFlush(any());
    }

    @Test
    void anEmptyDuplicateOrMalformedListFailsClosedAndWritesNothing() {
        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_EMPTY,
                refusalOf(List.of()));
        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_DUPLICATE,
                refusalOf(List.of(entry("anthropic", "claude-opus-5"),
                        entry("anthropic", "claude-opus-5"))));
        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_INVALID,
                refusalOf(List.of(priced("anthropic", "m", new BigDecimal("-1"), null,
                        new BigDecimal("1")))),
                "a negative price would silently credit spend back");
        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_INVALID,
                refusalOf(List.of(new InstanceModelProperties.ModelEntry("anthropic", "m",
                        1000, 2000, "CONSERVATIVE_RANGE", BigDecimal.ONE, null, BigDecimal.ONE))),
                "a model cannot output more than its own context window");
        assertEquals(ModelCatalogException.Code.MODEL_CATALOG_INVALID,
                refusalOf(List.of(new InstanceModelProperties.ModelEntry("anthropic", "m",
                        1000, 500, "GUESSWORK", BigDecimal.ONE, null, BigDecimal.ONE))),
                "an unrecognised tokenizer strategy must not fall back to a permissive default");

        verify(versions, never()).saveAndFlush(any());
    }

    @Test
    void anUnchangedListReusesItsVersionAndAChangedOneAppendsANewOne() {
        InstanceModelCatalogService first = service(
                List.of(entry("anthropic", "claude-opus-5")), "anthropic");
        UUID originalVersion = first.active().versionId();
        String originalHash = first.active().catalogSha256();

        // A separate service instance over the same repositories: same configuration, so the
        // stored version is adopted rather than duplicated.
        InstanceModelCatalogService again = service(
                List.of(entry("anthropic", "claude-opus-5")), "anthropic");
        assertEquals(originalVersion, again.active().versionId());
        assertEquals(1, stored.size(), "an unchanged price list must not append a version");

        // One price moves. History is not restated; a new version is appended beside it.
        InstanceModelCatalogService repriced = service(
                List.of(priced("anthropic", "claude-opus-5", new BigDecimal("7.00"), null,
                        new BigDecimal("25.00"))),
                "anthropic");
        assertNotEquals(originalVersion, repriced.active().versionId());
        assertNotEquals(originalHash, repriced.active().catalogSha256());
        assertEquals(2, stored.size());
    }

    @Test
    void theOrderModelsAreConfiguredInDoesNotChangeTheCatalogsIdentity() {
        InstanceModelCatalogService declaredOneWay = service(
                List.of(entry("anthropic", "claude-opus-5"), entry("anthropic", "claude-sonnet-5")),
                "anthropic");
        String forwards = declaredOneWay.active().catalogSha256();

        stored.clear();
        InstanceModelCatalogService declaredTheOther = service(
                List.of(entry("anthropic", "claude-sonnet-5"), entry("anthropic", "claude-opus-5")),
                "anthropic");

        assertEquals(forwards, declaredTheOther.active().catalogSha256(),
                "the same price list written in a different order is the same price list");
    }

    @Test
    void aScaleChangeIsARealChangeBecauseItIsAChangeToWhatWasWrittenDown() {
        InstanceModelCatalogService plain = service(
                List.of(priced("anthropic", "m", new BigDecimal("3.0"), null, BigDecimal.ONE)),
                "anthropic");
        String twoDigits = plain.active().catalogSha256();

        stored.clear();
        InstanceModelCatalogService padded = service(
                List.of(priced("anthropic", "m", new BigDecimal("3.00"), null, BigDecimal.ONE)),
                "anthropic");

        assertNotEquals(twoDigits, padded.active().catalogSha256());
    }

    @Test
    void anUnknownModelIsRefusedRatherThanGivenAFallbackPrice() {
        InstanceModelCatalogService catalog = service(
                List.of(entry("anthropic", "claude-opus-5")), "anthropic");

        ModelCatalogException refused = assertThrows(ModelCatalogException.class,
                () -> catalog.require("anthropic", "claude-not-configured"));

        assertEquals(ModelCatalogException.Code.MODEL_NOT_IN_CATALOG, refused.code());
        assertTrue(catalog.find("anthropic", "claude-not-configured").isEmpty());
        assertTrue(catalog.find(null, null).isEmpty());
    }

    @Test
    void aHistoricalVersionIsReadFromItsStoredRowsNotFromTodaysConfiguration() {
        UUID historical = UUID.randomUUID();
        when(entries.findByCatalogVersionIdAndProviderAndModel(historical, "anthropic", "old-model"))
                .thenReturn(Optional.of(new LabModelCatalogEntry(historical, "anthropic",
                        "old-model", 200_000, 8_000,
                        LabModelCatalogEntry.TokenizerStrategy.CONSERVATIVE_RANGE,
                        new BigDecimal("1.00"), null, new BigDecimal("2.00"))));
        InstanceModelCatalogService catalog = service(
                List.of(entry("anthropic", "claude-opus-5")), "anthropic");

        // Not in today's configuration at all, and priced at what it cost then.
        var priced = catalog.findInVersion(historical, "anthropic", "old-model").orElseThrow();
        assertEquals(new BigDecimal("1.00"), priced.inputUsdPerMillion());
        assertTrue(catalog.find("anthropic", "old-model").isEmpty());
    }

    // ================================================================ fixtures

    private ModelCatalogException.Code refusalOf(
            List<InstanceModelProperties.ModelEntry> configured) {
        InstanceModelCatalogService catalog = service(configured, "anthropic");
        return assertThrows(ModelCatalogException.class, catalog::available).code();
    }

    private InstanceModelCatalogService service(
            List<InstanceModelProperties.ModelEntry> configured, String... credentialedProviders) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiModelProvider> beans = mock(ObjectProvider.class);
        when(beans.stream()).thenAnswer(call -> java.util.Arrays.stream(credentialedProviders)
                .map(InstanceModelCatalogServiceTest::provider));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new InstanceModelCatalogService(new InstanceModelProperties(configured), beans,
                versions, entries, transactions);
    }

    private static AiModelProvider provider(String name) {
        AiModelProvider bean = mock(AiModelProvider.class);
        when(bean.getProviderName()).thenReturn(name);
        return bean;
    }

    private static InstanceModelProperties.ModelEntry entry(String provider, String model) {
        return priced(provider, model, new BigDecimal("5.00"), new BigDecimal("0.50"),
                new BigDecimal("25.00"));
    }

    private static InstanceModelProperties.ModelEntry priced(
            String provider, String model, BigDecimal input, BigDecimal cached, BigDecimal output) {
        return new InstanceModelProperties.ModelEntry(provider, model, 1_000_000, 128_000,
                "CONSERVATIVE_RANGE", input, cached, output);
    }
}
