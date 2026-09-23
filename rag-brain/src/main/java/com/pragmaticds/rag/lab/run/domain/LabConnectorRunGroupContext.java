package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Who created a run group through the connector surface, and for which tenant.
 *
 * <p>This row is an authorization fact, not bookkeeping: polling a connector-created group is
 * allowed only for the connector client and tenant recorded here, so a second tenant permitted on
 * the same token — or a second connector on the same brain — cannot read a group it did not
 * create. It is written once, in the same transaction as the group itself, and the database
 * refuses every UPDATE outright.
 *
 * <p>{@code externalRequestSha256} digests the <em>connector-visible</em> request: brain,
 * instance, tenant, package, revision, sorted sources, external request id. It is deliberately a
 * different digest from the group's own {@code request_sha256}, which hashes the resolved members
 * — including the release id the live pointer supplied at submission. A connector retry after a
 * promotion must replay the original group rather than being refused because live moved, and only
 * a digest over what the caller actually sent can decide that.
 *
 * <p>Plain ids on purpose — no {@code @ManyToOne} to the connector client or the group. A
 * relationship would let a serializer walk from this row to the client's token hash, and there is
 * no read path here that needs the joined entities.
 *
 * <p>Declared {@code @Immutable} with {@code updatable = false} columns so Hibernate can never
 * compose the {@code UPDATE} the database would refuse; see commit {@code 6517c35} and
 * {@link com.pragmaticds.rag.lab.domain.LabInstanceRelease} for the dirty-check round-trip that
 * made the declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_connector_run_group_context")
public class LabConnectorRunGroupContext {

    @Id
    @Column(name = "run_group_id", updatable = false)
    private UUID runGroupId;

    @Column(name = "connector_client_id", nullable = false, updatable = false)
    private UUID connectorClientId;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 120)
    private String tenantId;

    /** Opaque caller correlation id. Never a loan id, folder, filename, or parsed value. */
    @Column(name = "external_request_id", updatable = false, length = 120)
    private String externalRequestId;

    @Column(name = "external_request_sha256", nullable = false, updatable = false, length = 64)
    private String externalRequestSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LabConnectorRunGroupContext() {}

    public LabConnectorRunGroupContext(UUID runGroupId, UUID connectorClientId, UUID brainId,
                                       String tenantId, String externalRequestId,
                                       String externalRequestSha256) {
        this.runGroupId = runGroupId;
        this.connectorClientId = connectorClientId;
        this.brainId = brainId;
        this.tenantId = tenantId;
        this.externalRequestId = externalRequestId;
        this.externalRequestSha256 = externalRequestSha256;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getRunGroupId() { return runGroupId; }
    public UUID getConnectorClientId() { return connectorClientId; }
    public UUID getBrainId() { return brainId; }
    public String getTenantId() { return tenantId; }
    public String getExternalRequestId() { return externalRequestId; }
    public String getExternalRequestSha256() { return externalRequestSha256; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
