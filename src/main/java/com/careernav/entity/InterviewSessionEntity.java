package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "interview_sessions")
public class InterviewSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long resumeId; private Long jobId; private String jobTitle; private String difficulty;
    private String status; private Integer currentRound; private Integer totalRounds; private Integer overallScore;
    @Column(columnDefinition = "text") private String messagesJson;
    private LocalDateTime startedAt; private LocalDateTime updatedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public Long getResumeId() { return resumeId; } public void setResumeId(Long v) { resumeId = v; }
    public Long getJobId() { return jobId; } public void setJobId(Long v) { jobId = v; }
    public String getJobTitle() { return jobTitle; } public void setJobTitle(String v) { jobTitle = v; }
    public String getDifficulty() { return difficulty; } public void setDifficulty(String v) { difficulty = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Integer getCurrentRound() { return currentRound; } public void setCurrentRound(Integer v) { currentRound = v; }
    public Integer getTotalRounds() { return totalRounds; } public void setTotalRounds(Integer v) { totalRounds = v; }
    public Integer getOverallScore() { return overallScore; } public void setOverallScore(Integer v) { overallScore = v; }
    public String getMessagesJson() { return messagesJson; } public void setMessagesJson(String v) { messagesJson = v; }
    public LocalDateTime getStartedAt() { return startedAt; } public void setStartedAt(LocalDateTime v) { startedAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
