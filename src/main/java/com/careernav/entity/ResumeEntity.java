package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "resumes")
public class ResumeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80) private String ownerName;
    @Column(nullable = false, length = 120) private String targetRole;
    private String city;
    private String phone;
    private String email;
    private String education;
    private String school;
    private String major;
    private Integer graduationYear;
    @Column(columnDefinition = "text") private String skillsJson;
    @Column(columnDefinition = "text") private String projectsJson;
    @Column(columnDefinition = "text") private String experiencesJson;
    @Column(columnDefinition = "text") private String selfEvaluation;
    @Column(columnDefinition = "text") private String rawText;
    /** 事实锁：被锁定字段的 key 列表（JSON 数组），修改时校验。 */
    @Column(columnDefinition = "text") private String factLocksJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getOwnerName() { return ownerName; } public void setOwnerName(String v) { ownerName = v; }
    public String getTargetRole() { return targetRole; } public void setTargetRole(String v) { targetRole = v; }
    public String getCity() { return city; } public void setCity(String v) { city = v; }
    public String getPhone() { return phone; } public void setPhone(String v) { phone = v; }
    public String getEmail() { return email; } public void setEmail(String v) { email = v; }
    public String getEducation() { return education; } public void setEducation(String v) { education = v; }
    public String getSchool() { return school; } public void setSchool(String v) { school = v; }
    public String getMajor() { return major; } public void setMajor(String v) { major = v; }
    public Integer getGraduationYear() { return graduationYear; } public void setGraduationYear(Integer v) { graduationYear = v; }
    public String getSkillsJson() { return skillsJson; } public void setSkillsJson(String v) { skillsJson = v; }
    public String getProjectsJson() { return projectsJson; } public void setProjectsJson(String v) { projectsJson = v; }
    public String getExperiencesJson() { return experiencesJson; } public void setExperiencesJson(String v) { experiencesJson = v; }
    public String getSelfEvaluation() { return selfEvaluation; } public void setSelfEvaluation(String v) { selfEvaluation = v; }
    public String getRawText() { return rawText; } public void setRawText(String v) { rawText = v; }
    public String getFactLocksJson() { return factLocksJson; } public void setFactLocksJson(String v) { factLocksJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
