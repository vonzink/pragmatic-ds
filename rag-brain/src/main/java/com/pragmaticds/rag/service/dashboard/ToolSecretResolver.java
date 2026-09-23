package com.pragmaticds.rag.service.dashboard;

import java.util.Optional;

public interface ToolSecretResolver {
    Optional<String> resolve(String secretRef);
}
