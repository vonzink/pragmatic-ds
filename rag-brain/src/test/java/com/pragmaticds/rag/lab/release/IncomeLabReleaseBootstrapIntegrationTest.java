package com.pragmaticds.rag.lab.release;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes first-use Income bootstrap against a clean V35 schema: no registry parent is
 * test-seeded, yet the unchanged lazy release/pointer behavior remains available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class IncomeLabReleaseBootstrapIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainRepository brains;

    @Autowired
    LabInstanceRepository instances;

    @Autowired
    LabInstanceReleaseRepository releases;

    @Autowired
    LabInstancePointerRepository pointers;

    @Autowired
    PlatformTransactionManager transactionManager;

    private IncomeLabReleaseService service;

    @BeforeEach
    void setUp() {
        DomainPackRegistry registry = mock(DomainPackRegistry.class);
        RuntimeSettings settings = mock(RuntimeSettings.class);
        AnalyzerConfig income = new AnalyzerConfig(
                IncomeLabReleaseService.INCOME_ANALYZER_SLUG,
                "Income Analyzer (v2)",
                "Synthetic income prompt.",
                "income calculation and documentation guidelines",
                8,
                "Synthetic output schema.",
                null,
                null,
                "income",
                "v2",
                "federal-income",
                null);
        DomainPack pack = new DomainPack("bootstrap-test", "Bootstrap Test", null, null, null,
                null, null, null, null, null, null, null, List.of(income), null, null);
        when(registry.bundle(any(UUID.class)))
                .thenReturn(new BrainPackBundle(pack, Map.of(), List.of(), Map.of()));
        when(settings.answerProvider()).thenReturn("anthropic");

        service = new IncomeLabReleaseService(
                registry, brains, settings,
                new RagProperties.Routing("anthropic", "openai"),
                new LabManifestWriter(), instances, releases, pointers, transactionManager, 20_000);
    }

    @Test
    void firstUseBootstrapsIncomeFromACleanV35SchemaWithoutARegistryFixture() {
        assertTrue(instances.findByBrainIdAndSlug(
                TestBrains.DEFAULT_ID, IncomeLabReleaseService.INCOME_INSTANCE_SLUG).isEmpty());
        assertTrue(pointers.findByBrainIdAndInstanceSlug(
                TestBrains.DEFAULT_ID, IncomeLabReleaseService.INCOME_INSTANCE_SLUG).isEmpty());

        IncomeLabReleaseService.InstanceState state = new TransactionTemplate(transactionManager)
                .execute(status -> service.resolveInstance(TestBrains.DEFAULT_ID));

        assertNotNull(state);
        assertEquals(1, state.productionReleaseNumber());
        assertTrue(instances.findByBrainIdAndSlug(
                TestBrains.DEFAULT_ID, IncomeLabReleaseService.INCOME_INSTANCE_SLUG).isPresent());
        assertTrue(pointers.findByBrainIdAndInstanceSlug(
                TestBrains.DEFAULT_ID, IncomeLabReleaseService.INCOME_INSTANCE_SLUG).isPresent());
        assertTrue(releases.findByIdAndBrainIdAndInstanceSlug(state.productionReleaseId(),
                TestBrains.DEFAULT_ID, IncomeLabReleaseService.INCOME_INSTANCE_SLUG).isPresent());
    }
}
