package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifiedInstanceReleaseReaderTest {
    @Test
    void rejectsV2DigestMismatchBeforeReturningTypedMetadata() {
        LabManifestWriter writer = new LabManifestWriter();
        InstanceManifestCodec codec = InstanceManifestCodec.strict(writer);
        byte[] bytes = writer.canonicalize(codec.encode(v2()).json());
        LabInstanceRelease row = row("income", "0".repeat(64));
        LabInstanceReleaseRepository repository = mock(LabInstanceReleaseRepository.class);
        when(repository.findExactManifestJsonByIdIn(List.of(row.getId()))).thenReturn(List.of(raw(row, new String(bytes, StandardCharsets.UTF_8))));
        assertThrows(VerifiedInstanceReleaseReader.VerifiedReleaseException.class,
                () -> new VerifiedInstanceReleaseReader(repository, codec).read(row));
    }

    private static LabInstanceRelease row(String slug, String digest) {
        LabInstanceRelease row = new LabInstanceRelease();
        row.setId(UUID.randomUUID()); row.setBrainId(UUID.randomUUID()); row.setInstanceSlug(slug);
        row.setManifestSha256(digest); return row;
    }

    private static LabInstanceReleaseRepository.ExactManifestJson raw(LabInstanceRelease row, String json) {
        return new LabInstanceReleaseRepository.ExactManifestJson() {
            public UUID getId() { return row.getId(); }
            public String getManifestJson() { return json; }
        };
    }

    private static InstanceReleaseManifest v2() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1", "c", Set.of("W2"), Set.of("W2"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN, InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("openai", "model", InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract("p", "t", "q", new BigDecimal("0.10")), List.of(),
                new InstanceReleaseManifest.OutputContract("out", "a".repeat(64)),
                new InstanceReleaseManifest.LimitContract(1, 1, 1, 1, 1, new BigDecimal("1.00")),
                new InstanceReleaseManifest.EvaluationContract("set", 1, new BigDecimal("0.50")));
    }
}
