package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads exact JSONB text, verifies its canonical digest, then performs strict typed decoding. */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public final class VerifiedInstanceReleaseReader {
    private final LabInstanceReleaseRepository releases;
    private final InstanceManifestCodec codec;
    private final LabManifestWriter writer = new LabManifestWriter();

    public VerifiedInstanceReleaseReader(LabInstanceReleaseRepository releases, InstanceManifestCodec codec) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public DecodedInstanceManifest read(LabInstanceRelease release) {
        return readAll(List.of(release)).get(release.getId());
    }

    public Map<UUID, DecodedInstanceManifest> readAll(List<LabInstanceRelease> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<UUID, LabInstanceRelease> expected = new HashMap<>();
        for (LabInstanceRelease row : rows) expected.put(row.getId(), row);
        Map<UUID, DecodedInstanceManifest> decoded = new HashMap<>();
        for (LabInstanceReleaseRepository.ExactManifestJson raw : releases.findExactManifestJsonByIdIn(
                rows.stream().map(LabInstanceRelease::getId).toList())) {
            LabInstanceRelease row = expected.get(raw.getId());
            if (row == null || raw.getManifestJson() == null) throw unsupported();
            byte[] bytes = raw.getManifestJson().getBytes(StandardCharsets.UTF_8);
            try {
                Map<String, Object> exact = writer.readCanonical(bytes);
                if (!writer.sha256Hex(writer.canonicalize(exact)).equals(row.getManifestSha256())) throw unsupported();
                DecodedInstanceManifest manifest = codec.decode(bytes);
                if (manifest instanceof DecodedInstanceManifest.V1Income legacy
                        && !row.getInstanceSlug().equals(legacy.manifest().instanceSlug())) throw unsupported();
                decoded.put(row.getId(), manifest);
            } catch (RuntimeException invalid) {
                throw unsupported();
            }
        }
        if (decoded.size() != rows.size()) throw unsupported();
        return Map.copyOf(decoded);
    }

    private static VerifiedReleaseException unsupported() {
        return new VerifiedReleaseException();
    }

    /** Value-free so corrupt stored JSON can never become a response payload. */
    public static final class VerifiedReleaseException extends RuntimeException {
        public VerifiedReleaseException() { super("MANIFEST_UNSUPPORTED"); }
    }
}
