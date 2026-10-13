package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "job_source_policies", uniqueConstraints = @UniqueConstraint(columnNames = "source_code"))
public class JobSourcePolicyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_code", nullable = false, length = 80)
    private String sourceCode;
    @Column(nullable = false, length = 120)
    private String sourceName;
    @Column(length = 40, nullable = false)
    private String sourceType;
    @Column(length = 500)
    private String baseUrl;
    @Column(length = 500)
    private String feedUrl;
    @Column(length = 500)
    private String termsUrl;
    private boolean enabled;
    private boolean blocked;
    @Column(length = 120)
    private String status;
    @Column(length = 500)
    private String blockReason;
    @Column(length = 40)
    private String policyVersion;
    private Long crawlDelayMs;
    private Long requestIntervalMs;
    private LocalDateTime policyReviewedAt;
    private LocalDateTime lastCrawledAt;
    private LocalDateTime lastFullScanAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime nextAllowedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getSourceCode() { return sourceCode; } public void setSourceCode(String v) { sourceCode = v; }
    public String getSourceName() { return sourceName; } public void setSourceName(String v) { sourceName = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
    public String getFeedUrl() { return feedUrl; } public void setFeedUrl(String v) { feedUrl = v; }
    public String getTermsUrl() { return termsUrl; } public void setTermsUrl(String v) { termsUrl = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public boolean isBlocked() { return blocked; } public void setBlocked(boolean v) { blocked = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getBlockReason() { return blockReason; } public void setBlockReason(String v) { blockReason = v; }
    public String getPolicyVersion() { return policyVersion; } public void setPolicyVersion(String v) { policyVersion = v; }
    public Long getCrawlDelayMs() { return crawlDelayMs; } public void setCrawlDelayMs(Long v) { crawlDelayMs = v; }
    public Long getRequestIntervalMs() { return requestIntervalMs; } public void setRequestIntervalMs(Long v) { requestIntervalMs = v; }
    public LocalDateTime getPolicyReviewedAt() { return policyReviewedAt; } public void setPolicyReviewedAt(LocalDateTime v) { policyReviewedAt = v; }
    public LocalDateTime getLastCrawledAt() { return lastCrawledAt; } public void setLastCrawledAt(LocalDateTime v) { lastCrawledAt = v; }
    public LocalDateTime getLastFullScanAt() { return lastFullScanAt; } public void setLastFullScanAt(LocalDateTime v) { lastFullScanAt = v; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; } public void setLastSuccessAt(LocalDateTime v) { lastSuccessAt = v; }
    public LocalDateTime getNextAllowedAt() { return nextAllowedAt; } public void setNextAllowedAt(LocalDateTime v) { nextAllowedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
