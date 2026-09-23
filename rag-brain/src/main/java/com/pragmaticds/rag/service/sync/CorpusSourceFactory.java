package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.Brain;
import org.springframework.stereotype.Component;

/** Builds the CorpusSource a brain syncs from, by its source_type + binding columns. */
@Component
public class CorpusSourceFactory {

    public CorpusSource forBrain(Brain brain) {
        String type = brain.getSourceType();
        if ("local".equalsIgnoreCase(type)) {
            if (isBlank(brain.getLocalPath())) {
                throw new IllegalStateException(
                        "Brain '" + brain.getSlug() + "' is local but has no local_path");
            }
            return new LocalFolderCorpusSource(brain.getLocalPath());
        }
        if ("s3".equalsIgnoreCase(type)) {
            if (isBlank(brain.getS3Bucket())) {
                throw new IllegalStateException(
                        "Brain '" + brain.getSlug() + "' is s3 but has no s3_bucket");
            }
            return new S3CorpusSource(brain.getS3Bucket(), brain.getS3Prefix(), brain.getS3Region());
        }
        throw new IllegalStateException(
                "Brain '" + brain.getSlug() + "' has no/unknown source_type: " + type);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
