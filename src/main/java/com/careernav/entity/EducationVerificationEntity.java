package com.careernav.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 简历学历材料的最近一次核验结果。 */
@Entity
@Table(name = "education_verifications")
public class EducationVerificationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long resumeId;

    @Column(length = 40)
    private String status;

    @Column(length = 80)
    private String statusLabel;

    private Integer score;
    private Integer confidence;
    private Boolean passed;
    private Boolean officialReportDetected;

    @Column(length = 160)
    private String reportType;

    @Column(length = 32)
    private String proofFileType;

    @Column(length = 80)
    private String proofHash;

    @Column(length = 40)
    private String extractionMode;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String checksJson;

    @Column(columnDefinition = "text")
    private String warningsJson;

    private LocalDateTime checkedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long resumeId) { this.resumeId = resumeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public Boolean getOfficialReportDetected() { return officialReportDetected; }
    public void setOfficialReportDetected(Boolean officialReportDetected) { this.officialReportDetected = officialReportDetected; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getProofFileType() { return proofFileType; }
    public void setProofFileType(String proofFileType) { this.proofFileType = proofFileType; }
    public String getProofHash() { return proofHash; }
    public void setProofHash(String proofHash) { this.proofHash = proofHash; }
    public String getExtractionMode() { return extractionMode; }
    public void setExtractionMode(String extractionMode) { this.extractionMode = extractionMode; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getChecksJson() { return checksJson; }
    public void setChecksJson(String checksJson) { this.checksJson = checksJson; }
    public String getWarningsJson() { return warningsJson; }
    public void setWarningsJson(String warningsJson) { this.warningsJson = warningsJson; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}