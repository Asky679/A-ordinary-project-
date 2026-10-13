package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "job_market_snapshots", uniqueConstraints = @UniqueConstraint(columnNames = "month_key"))
public class JobMarketSnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "month_key", nullable = false, length = 20)
    private String monthKey;
    private Long totalJobs;
    private Long activeJobs;
    @Column(columnDefinition = "text") private String cityJson;
    @Column(columnDefinition = "text") private String skillJson;
    @Column(columnDefinition = "text") private String salaryJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getMonthKey() { return monthKey; } public void setMonthKey(String v) { monthKey = v; }
    public Long getTotalJobs() { return totalJobs; } public void setTotalJobs(Long v) { totalJobs = v; }
    public Long getActiveJobs() { return activeJobs; } public void setActiveJobs(Long v) { activeJobs = v; }
    public String getCityJson() { return cityJson; } public void setCityJson(String v) { cityJson = v; }
    public String getSkillJson() { return skillJson; } public void setSkillJson(String v) { skillJson = v; }
    public String getSalaryJson() { return salaryJson; } public void setSalaryJson(String v) { salaryJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
