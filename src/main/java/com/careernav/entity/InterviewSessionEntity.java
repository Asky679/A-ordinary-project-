package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "interview_sessions")
public class InterviewSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private Long userId;
    @Column(name = "data_kind", length = 20) private String dataKind;
    private Long resumeId; private Long jobId; private String jobTitle; private String difficulty; private String interviewMode;
    private String status; private Integer currentRound; private Integer totalRounds; private Integer overallScore;
    private String scoringVersion; private Integer legacyOverallScore;
    @Column(columnDefinition = "text") private String messagesJson;
    @Column(columnDefinition = "text") private String skillEvidenceJson;
    @Column(columnDefinition = "text") private String projectEvidenceJson;
    @Column(columnDefinition = "text") private String jobBlueprintJson;
    private LocalDateTime startedAt; private LocalDateTime updatedAt; private LocalDateTime deletedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getDataKind() { return dataKind; } public void setDataKind(String v) { dataKind = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public Long getResumeId() { return resumeId; } public void setResumeId(Long v) { resumeId = v; }
    public Long getJobId() { return jobId; } public void setJobId(Long v) { jobId = v; }
    public String getJobTitle() { return jobTitle; } public void setJobTitle(String v) { jobTitle = v; }
    public String getDifficulty() { return difficulty; } public void setDifficulty(String v) { difficulty = v; }
    public String getInterviewMode() { return interviewMode; } public void setInterviewMode(String v) { interviewMode = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Integer getCurrentRound() { return currentRound; } public void setCurrentRound(Integer v) { currentRound = v; }
    public Integer getTotalRounds() { return totalRounds; } public void setTotalRounds(Integer v) { totalRounds = v; }
    public Integer getOverallScore() { return overallScore; } public void setOverallScore(Integer v) { overallScore = v; }
    public String getScoringVersion() { return scoringVersion; } public void setScoringVersion(String v) { scoringVersion = v; }
    public Integer getLegacyOverallScore() { return legacyOverallScore; } public void setLegacyOverallScore(Integer v) { legacyOverallScore = v; }
    public String getMessagesJson() { return messagesJson; } public void setMessagesJson(String v) { messagesJson = v; }
    public String getSkillEvidenceJson() { return skillEvidenceJson; } public void setSkillEvidenceJson(String v) { skillEvidenceJson = v; }
    public String getProjectEvidenceJson() { return projectEvidenceJson; } public void setProjectEvidenceJson(String v) { projectEvidenceJson = v; }
    public String getJobBlueprintJson() { return jobBlueprintJson; } public void setJobBlueprintJson(String v) { jobBlueprintJson = v; }
    public LocalDateTime getStartedAt() { return startedAt; } public void setStartedAt(LocalDateTime v) { startedAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
    public LocalDateTime getDeletedAt() { return deletedAt; } public void setDeletedAt(LocalDateTime v) { deletedAt = v; }
}
