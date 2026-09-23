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
 * A curated page guide — "where the user should go" (spec §6.1). Full-CRUD
 * mutable row: editable route (nullable → topic-matched only)/title/purpose/
 * surface/user-intents/allowed-guidance/internal-links/source-link-ids/topics
 * plus a soft is_active flag. created_at/created_by are immutable; updated_at/
 * updated_by are touched on every write. jsonb list columns are mapped on typed
 * List fields via @JdbcTypeCode(SqlTypes.JSON): List&lt;String&gt; for intents/
 * guidance/topics, List&lt;LinkRef&gt; for inline internal links, List&lt;UUID&gt;
 * for the by-value references into brain_source_links (NOT a relational FK).
 */
@Entity
@Table(name = "brain_page_guides")
public class BrainPageGuide {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(length = 500)
    private String route;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Surface surface;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_intents", nullable = false, columnDefinition = "jsonb")
    private List<String> userIntents = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_guidance", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedGuidance = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "internal_links", nullable = false, columnDefinition = "jsonb")
    private List<LinkRef> internalLinks = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_link_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> sourceLinkIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> topics = new ArrayList<>();

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

    protected BrainPageGuide() {
    }

    public BrainPageGuide(UUID brainId, String route, String title, String purpose, Surface surface,
                          List<String> userIntents, List<String> allowedGuidance,
                          List<LinkRef> internalLinks, List<UUID> sourceLinkIds,
                          List<String> topics, String createdBy) {
        this.brainId = brainId;
        this.route = route;
        this.title = title;
        this.purpose = purpose;
        this.surface = surface;
        this.userIntents = userIntents == null ? new ArrayList<>() : new ArrayList<>(userIntents);
        this.allowedGuidance = allowedGuidance == null ? new ArrayList<>() : new ArrayList<>(allowedGuidance);
        this.internalLinks = internalLinks == null ? new ArrayList<>() : new ArrayList<>(internalLinks);
        this.sourceLinkIds = sourceLinkIds == null ? new ArrayList<>() : new ArrayList<>(sourceLinkIds);
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getBrainId() { return brainId; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public List<String> getUserIntents() { return userIntents; }
    public void setUserIntents(List<String> userIntents) { this.userIntents = userIntents; }

    public List<String> getAllowedGuidance() { return allowedGuidance; }
    public void setAllowedGuidance(List<String> allowedGuidance) { this.allowedGuidance = allowedGuidance; }

    public List<LinkRef> getInternalLinks() { return internalLinks; }
    public void setInternalLinks(List<LinkRef> internalLinks) { this.internalLinks = internalLinks; }

    public List<UUID> getSourceLinkIds() { return sourceLinkIds; }
    public void setSourceLinkIds(List<UUID> sourceLinkIds) { this.sourceLinkIds = sourceLinkIds; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
