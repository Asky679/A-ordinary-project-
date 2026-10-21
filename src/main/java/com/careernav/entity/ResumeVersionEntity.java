package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 简历版本快照：母版 / JD 定制版 / 英文版 / 归档版。 */
@Entity
@Table(name = "resume_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"resume_id", "version_no"}))
public class ResumeVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "resume_id", nullable = false) private Long resumeId;
    @Column(name = "version_no", nullable = false) private Integer versionNo;
    @Column(length = 40) private String label;
    private Long targetJobId;
    @Column(length = 255) private String targetJobTitle;
    private Integer matchScore;
    @Column(columnDefinition = "text") private String snapshotJson;
    @Column(columnDefinition = "text") private String factLocksJson;
    @Column(length = 500) private String note;
    private LocalDateTime createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getResumeId() { return resumeId; } public void setResumeId(Long v) { resumeId = v; }
    public Integer getVersionNo() { return versionNo; } public void setVersionNo(Integer v) { versionNo = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public Long getTargetJobId() { return targetJobId; } public void setTargetJobId(Long v) { targetJobId = v; }
    public String getTargetJobTitle() { return targetJobTitle; } public void setTargetJobTitle(String v) { targetJobTitle = v; }
    public Integer getMatchScore() { return matchScore; } public void setMatchScore(Integer v) { matchScore = v; }
    public String getSnapshotJson() { return snapshotJson; } public void setSnapshotJson(String v) { snapshotJson = v; }
    public String getFactLocksJson() { return factLocksJson; } public void setFactLocksJson(String v) { factLocksJson = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
