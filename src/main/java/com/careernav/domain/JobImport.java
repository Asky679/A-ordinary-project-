package com.careernav.domain;

import java.time.LocalDateTime;
import java.util.List;

public record JobImport(
        String externalId, String title, String company, String city, String salary,
        String education, String experience, List<String> skills,
        List<String> responsibilities, String description, String source,
        String sourceUrl, LocalDateTime collectedAt,
        LocalDateTime publishedAt, LocalDateTime validThrough,
        LocalDateTime lastSeenAt, String status, String contentHash, String policyVersion
) {
    public JobImport(String externalId, String title, String company, String city, String salary,
                     String education, String experience, List<String> skills,
                     List<String> responsibilities, String description, String source,
                     String sourceUrl, LocalDateTime collectedAt) {
        this(externalId, title, company, city, salary, education, experience, skills,
                responsibilities, description, source, sourceUrl, collectedAt,
                null, null, collectedAt, "ACTIVE", null, "legacy-v1");
    }
}
