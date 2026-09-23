package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceRegistryServiceTest {
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final InstanceKey INCOME = new InstanceKey(BRAIN, "income");

    private LabInstanceRepository instances;
    private InstanceRegistryService service;

    @BeforeEach
    void setUp() {
        instances = mock(LabInstanceRepository.class);
        service = new InstanceRegistryService(instances);
    }

    @Test
    void readsMetadataByBrainAndSlug() {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        when(instances.findByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.of(instance));
        when(instances.save(instance)).thenReturn(instance);

        assertSame(instance, service.require(INCOME));

        verify(instances).findByBrainIdAndSlug(BRAIN, "income");
    }

    @Test
    void updatesOnlyMutableMetadata() {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        when(instances.lockByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.of(instance));
        when(instances.save(instance)).thenReturn(instance);

        LabInstance updated = service.updateMetadata(INCOME, "Income review", "Verify income files");

        assertSame(instance, updated);
        assertEquals(BRAIN, updated.getBrainId());
        assertEquals("income", updated.getSlug());
        assertEquals("Income review", updated.getDisplayName());
        assertEquals("Verify income files", updated.getPurpose());
        verify(instances).save(instance);
    }

    @Test
    void disablesWithoutChangingIdentity() {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        when(instances.lockByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.of(instance));
        when(instances.save(instance)).thenReturn(instance);

        LabInstance disabled = service.disable(INCOME);

        assertEquals(LabInstance.State.DISABLED, disabled.getState());
        assertEquals(BRAIN, disabled.getBrainId());
        assertEquals("income", disabled.getSlug());
        verify(instances).save(instance);
    }

    @Test
    void restoresDisabledMetadata() {
        LabInstance instance = instance(LabInstance.State.DISABLED);
        when(instances.lockByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.of(instance));
        when(instances.save(instance)).thenReturn(instance);

        LabInstance restored = service.restore(INCOME);

        assertEquals(LabInstance.State.ACTIVE, restored.getState());
        verify(instances).save(instance);
    }

    @Test
    void missingBrainSlugIdentityIsNotFound() {
        when(instances.findByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.empty());

        InstanceRegistryService.InstanceException exception = assertThrows(
                InstanceRegistryService.InstanceException.class, () -> service.require(INCOME));

        assertEquals(InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND, exception.code());
    }

    private static LabInstance instance(LabInstance.State state) {
        LabInstance instance = new LabInstance(BRAIN, "income", "Income", "Evaluate income");
        instance.setState(state);
        return instance;
    }
}
