package com.careernav.service;

import com.careernav.domain.ApiModels.AgentMatchItem;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.JobEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 岗位 Agent 匹配结果缓存。
 *
 * <p>缓存键同时包含简历版本、岗位 ID、岗位内容版本和面试证据指纹，因此简历、JD 或面试证据
 * 更新后都不会命中旧结果。推荐页批量精排后写入缓存，诊断页会优先读取同一个结果，保证两个
 * 页面口径一致。</p>
 */
@Service
public class AgentMatchCacheService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 5000;

    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public AgentMatchItem get(ResumeView resume, JobEntity job) {
        CacheKey key = key(resume, job);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            cache.remove(key, entry);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.item();
    }

    public void put(ResumeView resume, JobEntity job, AgentMatchItem item) {
        if (resume == null || job == null || item == null || job.getId() == null) return;
        if (cache.size() >= MAX_ENTRIES) cleanup();
        cache.put(key(resume, job), new CacheEntry(item, Instant.now().plus(TTL)));
    }

    /** 学历核验等不修改简历正文、但会改变匹配输入的状态，需要显式失效该简历的缓存。 */
    public void invalidateResume(Long resumeId) {
        if (resumeId == null) return;
        cache.keySet().removeIf(key -> java.util.Objects.equals(key.resumeId(), resumeId));
    }

    public Map<String, Object> stats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        long total = hitCount + missCount;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", cache.size());
        result.put("ttlMinutes", TTL.toMinutes());
        result.put("hits", hitCount);
        result.put("misses", missCount);
        result.put("hitRate", total == 0 ? 0.0 : Math.round(hitCount * 1000.0 / total) / 10.0);
        return result;
    }

    private CacheKey key(ResumeView resume, JobEntity job) {
        LocalDateTime resumeVersion = resume.updatedAt() != null ? resume.updatedAt() : resume.createdAt();
        String jobVersion = job.getContentHash();
        if (jobVersion == null || jobVersion.isBlank()) jobVersion = job.getExternalId();
        String evidenceVersion = resume.interviewSkillEvidence() == null ? "" :
                resume.interviewSkillEvidence().stream()
                        .filter(item -> item != null)
                        .sorted(java.util.Comparator.comparing(item -> String.valueOf(item.skill())))
                        .map(item -> item.skill() + ":" + item.score() + ":" + item.verified() + ":"
                                + item.updatedAt())
                        .collect(java.util.stream.Collectors.joining("|"));
        return new CacheKey(resume.id(), job.getId(), resumeVersion, jobVersion, evidenceVersion);
    }

    private void cleanup() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (cache.size() < MAX_ENTRIES / 2) return;
        cache.entrySet().stream().limit(Math.max(1, cache.size() / 4))
                .map(Map.Entry::getKey).toList().forEach(cache::remove);
    }

    private record CacheKey(Long resumeId, Long jobId, LocalDateTime resumeVersion, String jobVersion,
                            String evidenceVersion) {}

    private record CacheEntry(AgentMatchItem item, Instant expiresAt) {}
}