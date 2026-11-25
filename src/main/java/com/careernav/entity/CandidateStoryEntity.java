package com.careernav.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_stories", indexes = {
        @Index(name = "idx_candidate_story_resume", columnList = "resumeId"),
        @Index(name = "idx_candidate_story_session", columnList = "sourceSessionId")
})
public class CandidateStoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private Long userId;
    @Column(name = "data_kind", length = 20) private String dataKind;
    private Long resumeId;
    private Long jobId;
    private Long sourceSessionId;
    @Column(columnDefinition = "text") private String sourceSessionIdsJson;
    private Integer sourceRound;
    private String interviewMode;
    private String title;
    private String projectName;
    @Column(columnDefinition = "text") private String situation;
    @Column(columnDefinition = "text") private String task;
    @Column(columnDefinition = "text") private String action;
    @Column(columnDefinition = "text") private String result;
    @Column(columnDefinition = "text") private String reflection;
    @Column(columnDefinition = "text") private String competenciesJson;
    @Column(columnDefinition = "text") private String evidence;
    private String fingerprint;
    private Integer confidence;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public String getDataKind() { return dataKind; } public void setDataKind(String v) { dataKind = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public void setId(Long value) { id = value; }
    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long value) { resumeId = value; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { jobId = value; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long value) { sourceSessionId = value; }
    public String getSourceSessionIdsJson() { return sourceSessionIdsJson; } public void setSourceSessionIdsJson(String value) { sourceSessionIdsJson = value; }
    public Integer getSourceRound() { return sourceRound; }
    public void setSourceRound(Integer value) { sourceRound = value; }
    public String getInterviewMode() { return interviewMode; }
    public void setInterviewMode(String value) { interviewMode = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName = value; }
    public String getSituation() { return situation; }
    public void setSituation(String value) { situation = value; }
    public String getTask() { return task; }
    public void setTask(String value) { task = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getResult() { return result; }
    public void setResult(String value) { result = value; }
    public String getReflection() { return reflection; }
    public void setReflection(String value) { reflection = value; }
    public String getCompetenciesJson() { return competenciesJson; }
    public void setCompetenciesJson(String value) { competenciesJson = value; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String value) { evidence = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { fingerprint = value; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer value) { confidence = value; }
    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer value) { usageCount = value; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime value) { lastUsedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
    public LocalDateTime getDeletedAt() { return deletedAt; } public void setDeletedAt(LocalDateTime value) { deletedAt = value; }
}
