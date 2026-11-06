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

    private Boolean qrDetected;
    @Column(length = 40) private String qrStatus;
    @Column(length = 120) private String qrStatusLabel;
    @Column(columnDefinition = "text") private String qrContent;
    @Column(length = 120) private String qrVerificationCode;
    @Column(columnDefinition = "text") private String officialVerifyUrl;
    private Boolean officialConfirmed;
    private LocalDateTime officialConfirmedAt;
    @Column(length = 120) private String proofOwnerName;
    @Column(length = 200) private String proofSchool;
    @Column(length = 80) private String proofEducation;
    @Column(length = 160) private String proofMajor;
    private Integer proofGraduationYear;
    @Column(length = 120) private String proofEducationCategory;
    @Column(length = 80) private String proofDuration;
    @Column(length = 120) private String proofStudyMode;
    @Column(length = 200) private String proofCollege;
    @Column(length = 200) private String proofDepartment;
    @Column(length = 80) private String proofEnrollmentDate;
    @Column(length = 120) private String proofStudentStatus;
    @Column(length = 80) private String proofExpectedGraduationDate;

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
    public Boolean getQrDetected() { return qrDetected; }
    public void setQrDetected(Boolean qrDetected) { this.qrDetected = qrDetected; }
    public String getQrStatus() { return qrStatus; }
    public void setQrStatus(String qrStatus) { this.qrStatus = qrStatus; }
    public String getQrStatusLabel() { return qrStatusLabel; }
    public void setQrStatusLabel(String qrStatusLabel) { this.qrStatusLabel = qrStatusLabel; }
    public String getQrContent() { return qrContent; }
    public void setQrContent(String qrContent) { this.qrContent = qrContent; }
    public String getQrVerificationCode() { return qrVerificationCode; }
    public void setQrVerificationCode(String qrVerificationCode) { this.qrVerificationCode = qrVerificationCode; }
    public String getOfficialVerifyUrl() { return officialVerifyUrl; }
    public void setOfficialVerifyUrl(String officialVerifyUrl) { this.officialVerifyUrl = officialVerifyUrl; }
    public Boolean getOfficialConfirmed() { return officialConfirmed; }
    public void setOfficialConfirmed(Boolean officialConfirmed) { this.officialConfirmed = officialConfirmed; }
    public LocalDateTime getOfficialConfirmedAt() { return officialConfirmedAt; }
    public void setOfficialConfirmedAt(LocalDateTime officialConfirmedAt) { this.officialConfirmedAt = officialConfirmedAt; }
    public String getProofOwnerName() { return proofOwnerName; }
    public void setProofOwnerName(String proofOwnerName) { this.proofOwnerName = proofOwnerName; }
    public String getProofSchool() { return proofSchool; }
    public void setProofSchool(String proofSchool) { this.proofSchool = proofSchool; }
    public String getProofEducation() { return proofEducation; }
    public void setProofEducation(String proofEducation) { this.proofEducation = proofEducation; }
    public String getProofMajor() { return proofMajor; }
    public void setProofMajor(String proofMajor) { this.proofMajor = proofMajor; }
    public Integer getProofGraduationYear() { return proofGraduationYear; }
    public void setProofGraduationYear(Integer proofGraduationYear) { this.proofGraduationYear = proofGraduationYear; }
    public String getProofEducationCategory() { return proofEducationCategory; }
    public void setProofEducationCategory(String v) { proofEducationCategory = v; }
    public String getProofDuration() { return proofDuration; }
    public void setProofDuration(String v) { proofDuration = v; }
    public String getProofStudyMode() { return proofStudyMode; }
    public void setProofStudyMode(String v) { proofStudyMode = v; }
    public String getProofCollege() { return proofCollege; }
    public void setProofCollege(String v) { proofCollege = v; }
    public String getProofDepartment() { return proofDepartment; }
    public void setProofDepartment(String v) { proofDepartment = v; }
    public String getProofEnrollmentDate() { return proofEnrollmentDate; }
    public void setProofEnrollmentDate(String v) { proofEnrollmentDate = v; }
    public String getProofStudentStatus() { return proofStudentStatus; }
    public void setProofStudentStatus(String v) { proofStudentStatus = v; }
    public String getProofExpectedGraduationDate() { return proofExpectedGraduationDate; }
    public void setProofExpectedGraduationDate(String v) { proofExpectedGraduationDate = v; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
