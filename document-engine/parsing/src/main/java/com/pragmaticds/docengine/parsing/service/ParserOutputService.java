package com.pragmaticds.docengine.parsing.service;

import com.pragmaticds.docengine.parsing.domain.ParserOutput;
import com.pragmaticds.docengine.parsing.repo.ParserOutputRepository;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists immutable raw stage output: the payload goes to blob storage under
 * {@code {orgId}/parser-output/{random}.json}; the row keeps the key and the sha256 digest.
 * Append-only — a retried stage attempt appends a new record rather than mutating history, which
 * is exactly what an audit of "what did the parser actually say" needs.
 */
@Service
public class ParserOutputService {

    private final ParserOutputRepository outputs;
    private final BlobStoragePort storage;

    public ParserOutputService(ParserOutputRepository outputs, BlobStoragePort storage) {
        this.outputs = outputs;
        this.storage = storage;
    }

    @Transactional
    public ParserOutput persist(
            UUID sourceFileId,
            UUID pageId,
            String stage,
            String parserName,
            String parserVersion,
            byte[] payload) {
        UUID orgId = TenantContext.require();
        String key = orgId + "/parser-output/" + UUID.randomUUID() + ".json";
        storage.put(key, payload);
        return outputs.save(
                new ParserOutput(
                        sourceFileId,
                        pageId,
                        stage,
                        parserName,
                        parserVersion,
                        key,
                        Digests.sha256Hex(payload)));
    }
}
