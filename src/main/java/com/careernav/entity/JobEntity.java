package com.careernav.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
public class JobEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true) private String externalId;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String company;
    private String city;
    private String salary;
    private String education;
    private String experience;
    @Column(columnDefinition = "text") private String skillsJson;
    @Column(columnDefinition = "text") private String responsibilitiesJson;
    @Column(columnDefinition = "text") private String description;
    @Column(columnDefinition = "text") private String jobDescription;
    @Column(columnDefinition = "text") private String jobRequirements;
    private String contentOrigin;
    private String aiModel;
    private LocalDateTime aiGeneratedAt;
    private BigDecimal aiConfidence;
    private String source;
    private String sourceUrl;
    private LocalDateTime collectedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime validThrough;
    private LocalDateTime lastSeenAt;
    @Column(length = 30)
    private String status;
    @Column(length = 80)
    private String category;
    private String industry;
    private String companySize;
    private String location;
    private String contentHash;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { externalId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getCompany() { return company; } public void setCompany(String v) { company = v; }
    public String getCity() { return city; } public void setCity(String v) { city = v; }
    public String getSalary() { return salary; } public void setSalary(String v) { salary = v; }
    public String getEducation() { return education; } public void setEducation(String v) { education = v; }
    public String getExperience() { return experience; } public void setExperience(String v) { experience = v; }
    public String getSkillsJson() { return skillsJson; } public void setSkillsJson(String v) { skillsJson = v; }
    public String getResponsibilitiesJson() { return responsibilitiesJson; } public void setResponsibilitiesJson(String v) { responsibilitiesJson = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getJobDescription() { return jobDescription; } public void setJobDescription(String v) { jobDescription = v; }
    public String getJobRequirements() { return jobRequirements; } public void setJobRequirements(String v) { jobRequirements = v; }
    public String getContentOrigin() { return contentOrigin; } public void setContentOrigin(String v) { contentOrigin = v; }
    public String getAiModel() { return aiModel; } public void setAiModel(String v) { aiModel = v; }
    public LocalDateTime getAiGeneratedAt() { return aiGeneratedAt; } public void setAiGeneratedAt(LocalDateTime v) { aiGeneratedAt = v; }
    public BigDecimal getAiConfidence() { return aiConfidence; } public void setAiConfidence(BigDecimal v) { aiConfidence = v; }
    public String getSource() { return source; } public void setSource(String v) { source = v; }
    public String getSourceUrl() { return sourceUrl; } public void setSourceUrl(String v) { sourceUrl = v; }
    public LocalDateTime getCollectedAt() { return collectedAt; } public void setCollectedAt(LocalDateTime v) { collectedAt = v; }
    public LocalDateTime getPublishedAt() { return publishedAt; } public void setPublishedAt(LocalDateTime v) { publishedAt = v; }
    public LocalDateTime getValidThrough() { return validThrough; } public void setValidThrough(LocalDateTime v) { validThrough = v; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; } public void setLastSeenAt(LocalDateTime v) { lastSeenAt = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getContentHash() { return contentHash; } public void setContentHash(String v) { contentHash = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public String getIndustry() { return industry; } public void setIndustry(String v) { industry = v; }
    public String getCompanySize() { return companySize; } public void setCompanySize(String v) { companySize = v; }
    public String getLocation() { return location; } public void setLocation(String v) { location = v; }
}
