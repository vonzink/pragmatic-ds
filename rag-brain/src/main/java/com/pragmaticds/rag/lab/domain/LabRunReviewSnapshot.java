package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** The read-model snapshot a run consumed: digest and counts, never values. Immutable. */
@Entity
@Immutable
@Table(name = "lab_run_review_snapshot")
public class LabRunReviewSnapshot {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "fields_sha256", nullable = false, updatable = false, length = 64)
    private String fieldsSha256;

    @Column(name = "document_count", nullable = false, updatable = false)
    private int documentCount;

    @Column(name = "machine_count", nullable = false, updatable = false)
    private int machineCount;

    @Column(name = "corrected_count", nullable = false, updatable = false)
    private int correctedCount;

    @Column(name = "rejected_count", nullable = false, updatable = false)
    private int rejectedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_versions", nullable = false, updatable = false)
    private Map<String, String> schemaVersions;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private OffsetDateTime capturedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (capturedAt == null) {
            capturedAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getFieldsSha256() { return fieldsSha256; }
    public void setFieldsSha256(String fieldsSha256) { this.fieldsSha256 = fieldsSha256; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public int getMachineCount() { return machineCount; }
    public void setMachineCount(int machineCount) { this.machineCount = machineCount; }
    public int getCorrectedCount() { return correctedCount; }
    public void setCorrectedCount(int correctedCount) { this.correctedCount = correctedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public void setRejectedCount(int rejectedCount) { this.rejectedCount = rejectedCount; }
    public Map<String, String> getSchemaVersions() { return schemaVersions; }
    public void setSchemaVersions(Map<String, String> schemaVersions) { this.schemaVersions = schemaVersions; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
}
