package com.pragmaticds.docengine.platform.ai;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;

/** Resolves Google Application Default Credentials without exposing credential content. */
@FunctionalInterface
public interface VertexCredentialsProvider {

    String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    GoogleCredentials credentials() throws IOException;

    static VertexCredentialsProvider applicationDefault() {
        return () ->
                GoogleCredentials.getApplicationDefault()
                        .createScoped(CLOUD_PLATFORM_SCOPE);
    }
}
