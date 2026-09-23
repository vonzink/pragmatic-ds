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
import java.util.List;
import java.util.UUID;

/** Immutable safe response snapshot referenced by a body-free idempotency receipt. */
@Entity
@Immutable
@Table(name = "lab_instance_command_result")
public class LabInstanceCommandResult {
    @Id private UUID id;
    @Column(name = "brain_id", nullable = false, updatable = false) private UUID brainId;
    @Column(name = "instance_slug", nullable = false, updatable = false) private String instanceSlug;
    @Column(name = "display_name", nullable = false, updatable = false) private String displayName;
    @Column(nullable = false, updatable = false) private String purpose;
    @Column(nullable = false, updatable = false) private String state;
    @Column(name = "instance_created_at", nullable = false, updatable = false) private OffsetDateTime instanceCreatedAt;
    @Column(name = "instance_updated_at", nullable = false, updatable = false) private OffsetDateTime instanceUpdatedAt;
    @Column(name = "candidate_count", nullable = false, updatable = false) private int candidateCount;
    @Column(name = "has_candidate_release", nullable = false, updatable = false) private boolean hasCandidateRelease;
    @Column(name = "live_release_id", updatable = false) private UUID liveReleaseId;
    @Column(name = "live_release_number", updatable = false) private Integer liveReleaseNumber;
    @Column(name = "live_provenance", updatable = false) private String liveProvenance;
    @Column(name = "live_manifest_version", updatable = false) private Integer liveManifestVersion;
    @Column(name = "live_provider", updatable = false) private String liveProvider;
    @Column(name = "live_model", updatable = false) private String liveModel;
    @Column(name = "live_collection_count", updatable = false) private Integer liveCollectionCount;
    @Column(name = "live_limitation_code", updatable = false) private String liveLimitationCode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "live_limitation_flags", nullable = false, updatable = false,
            columnDefinition = "jsonb") private List<String> liveLimitationFlags;
    @Column(name = "live_created_at", updatable = false) private OffsetDateTime liveCreatedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    public LabInstanceCommandResult() {}
    @PrePersist void created() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = OffsetDateTime.now(); }
    public UUID getId(){return id;} public UUID getBrainId(){return brainId;} public String getInstanceSlug(){return instanceSlug;}
    public String getDisplayName(){return displayName;} public String getPurpose(){return purpose;} public String getState(){return state;}
    public OffsetDateTime getInstanceCreatedAt(){return instanceCreatedAt;} public OffsetDateTime getInstanceUpdatedAt(){return instanceUpdatedAt;}
    public int getCandidateCount(){return candidateCount;} public boolean isHasCandidateRelease(){return hasCandidateRelease;}
    public UUID getLiveReleaseId(){return liveReleaseId;} public Integer getLiveReleaseNumber(){return liveReleaseNumber;}
    public String getLiveProvenance(){return liveProvenance;} public Integer getLiveManifestVersion(){return liveManifestVersion;}
    public String getLiveProvider(){return liveProvider;} public String getLiveModel(){return liveModel;}
    public Integer getLiveCollectionCount(){return liveCollectionCount;} public String getLiveLimitationCode(){return liveLimitationCode;}
    public List<String> getLiveLimitationFlags(){return liveLimitationFlags;} public OffsetDateTime getLiveCreatedAt(){return liveCreatedAt;}
    public OffsetDateTime getCreatedAt(){return createdAt;}
    public void setBrainId(UUID value){brainId=value;} public void setInstanceSlug(String value){instanceSlug=value;}
    public void setDisplayName(String value){displayName=value;} public void setPurpose(String value){purpose=value;} public void setState(String value){state=value;}
    public void setInstanceCreatedAt(OffsetDateTime value){instanceCreatedAt=value;} public void setInstanceUpdatedAt(OffsetDateTime value){instanceUpdatedAt=value;}
    public void setCandidateCount(int value){candidateCount=value;} public void setHasCandidateRelease(boolean value){hasCandidateRelease=value;}
    public void setLiveReleaseId(UUID value){liveReleaseId=value;} public void setLiveReleaseNumber(Integer value){liveReleaseNumber=value;}
    public void setLiveProvenance(String value){liveProvenance=value;} public void setLiveManifestVersion(Integer value){liveManifestVersion=value;}
    public void setLiveProvider(String value){liveProvider=value;} public void setLiveModel(String value){liveModel=value;}
    public void setLiveCollectionCount(Integer value){liveCollectionCount=value;} public void setLiveLimitationCode(String value){liveLimitationCode=value;}
    public void setLiveLimitationFlags(List<String> value){liveLimitationFlags=List.copyOf(value);} public void setLiveCreatedAt(OffsetDateTime value){liveCreatedAt=value;}
}
