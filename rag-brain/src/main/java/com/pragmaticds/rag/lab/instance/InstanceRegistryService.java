package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mutable administrative metadata for immutable brain/slug instance identities. */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceRegistryService {
    private final LabInstanceRepository instances;

    public InstanceRegistryService(LabInstanceRepository instances) {
        this.instances = Objects.requireNonNull(instances, "instances");
    }

    @Transactional(readOnly = true)
    public LabInstance require(InstanceKey key) {
        return instances.findByBrainIdAndSlug(key.brainId(), key.slug())
                .orElseThrow(() -> new InstanceException(InstanceException.Code.INSTANCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<LabInstance> list(UUID brainId) {
        return instances.findAllByBrainIdOrderByDisplayNameAsc(brainId);
    }

    @Transactional
    public LabInstance updateMetadata(InstanceKey key, String displayName, String purpose) {
        LabInstance instance = lock(key);
        requireActive(instance);
        instance.setDisplayName(Objects.requireNonNull(displayName, "displayName"));
        instance.setPurpose(Objects.requireNonNull(purpose, "purpose"));
        return instances.save(instance);
    }

    @Transactional
    public LabInstance disable(InstanceKey key) {
        LabInstance instance = lock(key);
        requireActive(instance);
        instance.setState(LabInstance.State.DISABLED);
        return instances.save(instance);
    }

    @Transactional
    public LabInstance restore(InstanceKey key) {
        LabInstance instance = lock(key);
        instance.setState(LabInstance.State.ACTIVE);
        return instances.save(instance);
    }

    private LabInstance lock(InstanceKey key) {
        return instances.lockByBrainIdAndSlug(key.brainId(), key.slug())
                .orElseThrow(() -> new InstanceException(InstanceException.Code.INSTANCE_NOT_FOUND));
    }

    private static void requireActive(LabInstance instance) {
        if (instance.getState() == LabInstance.State.DISABLED) {
            throw new InstanceException(InstanceException.Code.INSTANCE_DISABLED);
        }
    }

    /** Stable, value-free errors for callers that must not learn another brain's identity. */
    public static final class InstanceException extends RuntimeException {
        public enum Code { INSTANCE_NOT_FOUND, INSTANCE_DISABLED }
        private final Code code;
        public InstanceException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }
        public Code code() { return code; }
    }
}
