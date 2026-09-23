package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.Brain;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorpusSourceFactoryTest {

    private final CorpusSourceFactory factory = new CorpusSourceFactory();

    @Test
    void localBrainReturnsLocalFolderCorpusSource() {
        Brain brain = new Brain(UUID.randomUUID(), "local-brain", "Local Brain");
        brain.setSourceType("local");
        brain.setLocalPath("/tmp/some-folder");

        assertInstanceOf(LocalFolderCorpusSource.class, factory.forBrain(brain));
    }

    @Test
    void s3BrainReturnsS3CorpusSource() {
        Brain brain = new Brain(UUID.randomUUID(), "s3-brain", "S3 Brain");
        brain.setSourceType("s3");
        brain.setS3Bucket("my-bucket");
        brain.setS3Prefix("prefix/");
        brain.setS3Region("us-east-1");

        // S3Client construction makes no network call; safe offline
        assertInstanceOf(S3CorpusSource.class, factory.forBrain(brain));
    }

    @Test
    void localBrainWithBlankPathThrows() {
        Brain brain = new Brain(UUID.randomUUID(), "broken", "Broken");
        brain.setSourceType("local");
        // localPath intentionally not set

        assertThrows(IllegalStateException.class, () -> factory.forBrain(brain));
    }

    @Test
    void s3BrainWithBlankBucketThrows() {
        Brain brain = new Brain(UUID.randomUUID(), "broken-s3", "Broken S3");
        brain.setSourceType("s3");
        // s3Bucket intentionally not set

        assertThrows(IllegalStateException.class, () -> factory.forBrain(brain));
    }

    @Test
    void unknownSourceTypeThrows() {
        Brain brain = new Brain(UUID.randomUUID(), "mystery", "Mystery");
        brain.setSourceType("ftp");

        assertThrows(IllegalStateException.class, () -> factory.forBrain(brain));
    }

    @Test
    void nullSourceTypeThrows() {
        Brain brain = new Brain(UUID.randomUUID(), "no-type", "No Type");
        // sourceType not set → null

        assertThrows(IllegalStateException.class, () -> factory.forBrain(brain));
    }
}
