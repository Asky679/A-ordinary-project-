package com.careernav.service;

import com.careernav.config.AppProperties;
import com.careernav.domain.JobImport;
import com.careernav.entity.JobCollectionRunEntity;
import com.careernav.entity.JobEntity;
import com.careernav.entity.JobSourcePolicyEntity;
import com.careernav.repository.JobCollectionRunRepository;
import com.careernav.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JobCollectionService {
    private final AppProperties properties;
    private final JobRepository jobRepository;
    private final JobCollectionRunRepository runRepository;
    private final JobService jobService;
    private final McpJobGateway mcpJobGateway;
    private final SourcePolicyService policyService;
    private final CompliantHttpFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final JobMarketAnalyticsService analyticsService;
    private final BossZhipinPublicHtmlProvider bossZhipinProvider;

    public JobCollectionService(AppProperties properties, JobRepository jobRepository,
                                JobCollectionRunRepository runRepository, JobService jobService,
                                McpJobGateway mcpJobGateway, SourcePolicyService policyService,
                                CompliantHttpFetcher fetcher, ObjectMapper objectMapper,
                                JobMarketAnalyticsService analyticsService,
                                BossZhipinPublicHtmlProvider bossZhipinProvider) {
        this.properties = properties;
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.jobService = jobService;
        this.mcpJobGateway = mcpJobGateway;
        this.policyService = policyService;
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.analyticsService = analyticsService;
        this.bossZhipinProvider = bossZhipinProvider;
    }

    public JobCollectionRunEntity runExpiryCheck() {
        JobCollectionRunEntity run = begin("SYSTEM", "EXPIRY_CHECK", "system");
        int expired = 0;
        int stale = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            for (JobEntity job : jobRepository.findAll()) {
                String old = job.getStatus();
                String next = lifecycle(job, now);
                if (!next.equals(old)) {
                    job.setStatus(next);
                    if ("EXPIRED".equals(next)) {
                        // 历史归档只保留有价值的结构化字段和来源链接，不继续保留岗位全文。
                        job.setDescription(null);
                        job.setResponsibilitiesJson("[]");
                    }
                    jobRepository.save(job);
                    if ("EXPIRED".equals(next)) expired++;
                    if ("STALE".equals(next)) stale++;
                }
            }
            return finish(run, "SUCCESS",
                    "过期岗位 " + expired + " 个，陈旧岗位 " + stale + " 个", 0, 0, expired);
        } catch (Exception exception) {
            return finish(run, "FAILED", exception.getMessage(), 0, 0, expired);
        }
    }

    public JobCollectionRunEntity runIncremental() {
        return collect("INCREMENTAL", 100, false);
    }

    public JobCollectionRunEntity runFull() {
        return collect("FULL", 500, true);
    }

    public JobCollectionRunEntity runMarketSnapshot() {
        JobCollectionRunEntity run = begin("SYSTEM", "MARKET_STATS", "system");
        try {
            var snapshot = analyticsService.captureMonthly();
            return finish(run, "SUCCESS",
                    "已生成 " + snapshot.monthKey() + " 市场快照，活跃岗位 " + snapshot.activeJobs(), 0, 0, 0);
        } catch (Exception exception) {
            return finish(run, "FAILED", exception.getMessage(), 0, 0, 0);
        }
    }

    public JobCollectionRunEntity runNow(String type) {
        return switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "INCREMENTAL" -> runIncremental();
            case "FULL" -> runFull();
            case "EXPIRY_CHECK" -> runExpiryCheck();
            case "MARKET_STATS" -> runMarketSnapshot();
            default -> throw new com.careernav.common.BusinessException("未知采集任务类型：" + type);
        };
    }

    private JobCollectionRunEntity collect(String runType, int limit, boolean full) {
        List<JobSourcePolicyEntity> sources = policyService.list().stream()
                .filter(JobSourcePolicyEntity::isEnabled)
                .filter(policy -> java.util.Set.of("MCP", "JSON_FEED", "BOSS_PUBLIC_HTML").contains(policy.getSourceType()))
                .toList();
        if (sources.isEmpty()) {
            JobCollectionRunEntity run = begin("SYSTEM", runType, "none");
            return finish(run, "SKIPPED", "没有启用的授权数据来源", 0, 0, 0);
        }
        JobCollectionRunEntity last = null;
        for (JobSourcePolicyEntity source : sources) {
            last = collectSource(source, runType, limit, full);
        }
        return last;
    }

    private JobCollectionRunEntity collectSource(JobSourcePolicyEntity policy,
                                                  String runType, int limit, boolean full) {
        JobCollectionRunEntity run = begin(policy.getSourceCode(), runType, policy.getPolicyVersion());
        try {
            policyService.assertAllowed(policy.getSourceCode());
            List<JobImport> jobs;
            if ("MCP".equals(policy.getSourceType())) {
                jobs = mcpJobGateway.fetchJobs("", "", limit);
            } else if ("BOSS_PUBLIC_HTML".equals(policy.getSourceType())) {
                jobs = bossZhipinProvider.fetch(limit);
            } else {
                jobs = fetchJsonFeed(policy, limit);
            }
            List<?> imported = jobs.isEmpty() ? List.of() : jobService.importJobs(jobs);
            if ("BOSS_PUBLIC_HTML".equals(policy.getSourceType())) reconcileBossJobs(jobs);
            if (full) policyService.markFullScan(policy.getSourceCode());
            return finish(run, jobs.isEmpty() && mcpJobGateway.enabled() ? "PARTIAL" : "SUCCESS",
                    jobs.isEmpty() ? "本次未返回新岗位，保留现有数据" : "已处理 " + jobs.size() + " 个岗位",
                    1, imported.size(), 0);
        } catch (Exception exception) {
            return finish(run, "FAILED", exception.getMessage(), 1, 0, 0);
        }
    }

    private void reconcileBossJobs(List<JobImport> current) {
        Set<String> seen = current.stream().map(JobImport::externalId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (JobEntity entity : jobRepository.findBySource("BOSS直聘公开城市页")) {
            if (!seen.contains(entity.getExternalId()) && !"STALE".equals(entity.getStatus())) {
                entity.setStatus("STALE");
                jobRepository.save(entity);
            }
        }
    }

    private List<JobImport> fetchJsonFeed(JobSourcePolicyEntity policy, int limit) throws Exception {
        if (policy.getFeedUrl() == null || policy.getFeedUrl().isBlank()) return List.of();
        CompliantHttpFetcher.FetchResult result = fetcher.get(policy.getSourceCode(), policy.getFeedUrl());
        if (!result.successful()) {
            throw new com.careernav.common.BusinessException("JSON Feed 返回 HTTP " + result.statusCode());
        }
        JsonNode root = objectMapper.readTree(result.body());
        if (root.has("jobs")) root = root.path("jobs");
        if (root.has("data")) root = root.path("data");
        if (!root.isArray()) throw new com.careernav.common.BusinessException("JSON Feed 必须返回数组或 jobs 数组");
        List<JobImport> jobs = new ArrayList<>();
        for (JsonNode node : root) {
            if (jobs.size() >= limit) break;
            String title = node.path("title").asText("");
            String company = node.path("company").asText("");
            if (title.isBlank() || company.isBlank()) continue;
            String externalId = node.path("externalId").asText(node.path("id").asText(null));
            if (externalId == null || externalId.isBlank()) {
                externalId = policy.getSourceCode() + "-" + Integer.toUnsignedString((title + company).hashCode());
            }
            jobs.add(new JobImport(externalId, title, company, node.path("city").asText(""),
                    node.path("salary").asText(""), node.path("education").asText("不限"),
                    node.path("experience").asText("不限"), strings(node.path("skills")),
                    strings(node.path("responsibilities")), node.path("description").asText(""),
                    policy.getSourceName(), node.path("sourceUrl").asText(""), LocalDateTime.now(),
                    parseDate(node.path("publishedAt").asText(null)),
                    parseDate(node.path("validThrough").asText(null)), LocalDateTime.now(),
                    "ACTIVE", null, policy.getPolicyVersion()));
        }
        return jobs;
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); }
        catch (Exception ignored) { }
        try { return OffsetDateTime.parse(value).toLocalDateTime(); }
        catch (Exception ignored) { return null; }
    }

    private String lifecycle(JobEntity job, LocalDateTime now) {
        if (job.getValidThrough() != null && job.getValidThrough().isBefore(now)) return "EXPIRED";
        LocalDateTime lastSeen = job.getLastSeenAt() != null ? job.getLastSeenAt() : job.getCollectedAt();
        if (lastSeen != null && lastSeen.isBefore(now.minusDays(properties.getCollection().getExpireDays()))) {
            return "EXPIRED";
        }
        if (lastSeen != null && lastSeen.isBefore(now.minusDays(properties.getCollection().getStaleDays()))) {
            return "STALE";
        }
        return "ACTIVE";
    }

    private JobCollectionRunEntity begin(String sourceCode, String type, String policyVersion) {
        JobCollectionRunEntity run = new JobCollectionRunEntity();
        run.setSourceCode(sourceCode);
        run.setRunType(type);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        run.setRequestCount(0);
        run.setImportedCount(0);
        run.setSkippedCount(0);
        run.setExpiredCount(0);
        run.setPolicyVersion(policyVersion);
        return runRepository.save(run);
    }

    private JobCollectionRunEntity finish(JobCollectionRunEntity run, String status, String message,
                                          int requests, int imported, int expired) {
        run.setStatus(status);
        run.setMessage(message == null ? "" : message.substring(0, Math.min(1000, message.length())));
        run.setRequestCount(requests);
        run.setImportedCount(imported);
        run.setExpiredCount(expired);
        run.setFinishedAt(LocalDateTime.now());
        return runRepository.save(run);
    }
}
