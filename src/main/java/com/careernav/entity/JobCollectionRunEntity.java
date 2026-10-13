package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "job_collection_runs")
public class JobCollectionRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 80)
    private String sourceCode;
    @Column(length = 40, nullable = false)
    private String runType;
    @Column(length = 40, nullable = false)
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer requestCount;
    private Integer importedCount;
    private Integer skippedCount;
    private Integer expiredCount;
    @Column(length = 1000)
    private String message;
    @Column(length = 40)
    private String policyVersion;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getSourceCode() { return sourceCode; } public void setSourceCode(String v) { sourceCode = v; }
    public String getRunType() { return runType; } public void setRunType(String v) { runType = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getStartedAt() { return startedAt; } public void setStartedAt(LocalDateTime v) { startedAt = v; }
    public LocalDateTime getFinishedAt() { return finishedAt; } public void setFinishedAt(LocalDateTime v) { finishedAt = v; }
    public Integer getRequestCount() { return requestCount; } public void setRequestCount(Integer v) { requestCount = v; }
    public Integer getImportedCount() { return importedCount; } public void setImportedCount(Integer v) { importedCount = v; }
    public Integer getSkippedCount() { return skippedCount; } public void setSkippedCount(Integer v) { skippedCount = v; }
    public Integer getExpiredCount() { return expiredCount; } public void setExpiredCount(Integer v) { expiredCount = v; }
    public String getMessage() { return message; } public void setMessage(String v) { message = v; }
    public String getPolicyVersion() { return policyVersion; } public void setPolicyVersion(String v) { policyVersion = v; }
}
