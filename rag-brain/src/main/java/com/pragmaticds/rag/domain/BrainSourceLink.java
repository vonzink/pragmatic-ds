package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A curated external source/link the brain is allowed to cite or surface (the
 * trust layer, spec §6.1). Full-CRUD mutable row: editable name/url/domain/
 * authority/topics/freshness/allowed-use/do-not-use-for/surface plus a soft
 * is_active flag. created_at/created_by are immutable; updated_at/updated_by are
 * touched on every write. jsonb list columns are mapped on typed List&lt;String&gt;
 * fields via @JdbcTypeCode(SqlTypes.JSON) (the repo's only jsonb idiom).
 */
@Entity
@Table(name = "brain_source_links")
public class BrainSourceLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(length = 255)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LinkAuthority authority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> topics = new ArrayList<>();

    @Column(name = "freshness_required", nullable = false)
    private boolean freshnessRequired = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_use", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedUse = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "do_not_use_for", nullable = false, columnDefinition = "jsonb")
    private List<String> doNotUseFor = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Surface surface;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    protected BrainSourceLink() {
    }

    public BrainSourceLink(UUID brainId, String name, String url, String domain, LinkAuthority authority,
                           List<String> topics, boolean freshnessRequired, List<String> allowedUse,
                           List<String> doNotUseFor, Surface surface, String createdBy) {
        this.brainId = brainId;
        this.name = name;
        this.url = url;
        this.domain = domain;
        this.authority = authority;
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
        this.freshnessRequired = freshnessRequired;
        this.allowedUse = allowedUse == null ? new ArrayList<>() : new ArrayList<>(allowedUse);
        this.doNotUseFor = doNotUseFor == null ? new ArrayList<>() : new ArrayList<>(doNotUseFor);
        this.surface = surface;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getBrainId() { return brainId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public LinkAuthority getAuthority() { return authority; }
    public void setAuthority(LinkAuthority authority) { this.authority = authority; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public boolean isFreshnessRequired() { return freshnessRequired; }
    public void setFreshnessRequired(boolean freshnessRequired) { this.freshnessRequired = freshnessRequired; }

    public List<String> getAllowedUse() { return allowedUse; }
    public void setAllowedUse(List<String> allowedUse) { this.allowedUse = allowedUse; }

    public List<String> getDoNotUseFor() { return doNotUseFor; }
    public void setDoNotUseFor(List<String> doNotUseFor) { this.doNotUseFor = doNotUseFor; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
