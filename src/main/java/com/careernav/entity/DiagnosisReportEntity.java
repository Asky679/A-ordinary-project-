package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "diagnosis_reports")
public class DiagnosisReportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long resumeId; private Long jobId; private String targetRole; private Integer overallScore; private Integer resumeQualityScore; private Integer jobMatchScore;
    @Column(columnDefinition = "text") private String reportJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getResumeId() { return resumeId; } public void setResumeId(Long v) { resumeId = v; }
    public Long getJobId() { return jobId; } public void setJobId(Long v) { jobId = v; }
    public String getTargetRole() { return targetRole; } public void setTargetRole(String v) { targetRole = v; }
    public Integer getOverallScore() { return overallScore; } public void setOverallScore(Integer v) { overallScore = v; }
    public Integer getResumeQualityScore() { return resumeQualityScore; } public void setResumeQualityScore(Integer v) { resumeQualityScore = v; }
    public Integer getJobMatchScore() { return jobMatchScore; } public void setJobMatchScore(Integer v) { jobMatchScore = v; }
    public String getReportJson() { return reportJson; } public void setReportJson(String v) { reportJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
