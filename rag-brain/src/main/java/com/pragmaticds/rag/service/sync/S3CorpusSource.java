package com.pragmaticds.rag.service.sync;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3-backed corpus (mirror of scripts/s3-ingest/sync.mjs listCorpus/s3Bytes).
 * The client uses the default AWS credentials chain; region/bucket/prefix are
 * supplied by CorpusSourceFactory from the brain's binding columns.
 */
public class S3CorpusSource implements CorpusSource {

    static final String MANIFEST_NAME = "_manifest.json";

    private final String bucket;
    private final String prefix;
    private final S3Client s3;

    public S3CorpusSource(String bucket, String prefix, String region) {
        this.bucket = bucket;
        this.prefix = prefix;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public List<String> listFiles() {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).continuationToken(token).build());
            page.contents().forEach(o -> keys.add(o.key()));
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        return filterKeys(prefix, keys);
    }

    /** Pure: strip the prefix; drop the prefix key itself, the manifest, and folder markers. */
    static List<String> filterKeys(String prefix, List<String> keys) {
        return keys.stream()
                .filter(k -> !k.equals(prefix))
                .filter(k -> !k.equals(prefix + MANIFEST_NAME))
                .filter(k -> !k.endsWith("/"))
                .map(k -> k.substring(prefix.length()))
                .toList();
    }

    @Override
    public byte[] fetch(String fileName) {
        ResponseBytes<?> bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(prefix + fileName).build());
        return bytes.asByteArray();
    }

    @Override
    public Optional<byte[]> fetchManifest() {
        try {
            return Optional.of(fetch(MANIFEST_NAME));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
