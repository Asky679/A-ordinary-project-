package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.careernav.domain.JobImport;
import com.careernav.entity.JobEntity;
import com.careernav.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JobService {
    private static final int MAX_WEIGHT = 50;
    private static final Pattern EXPERIENCE_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~至]\\s*(\\d+(?:\\.\\d+)?)\\s*年");
    private static final Pattern EXPERIENCE_ABOVE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*年(?:以上|及以上)");
    private static final Pattern EXPERIENCE_BELOW = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*年以内");
    private static final Pattern PERIOD_DATE = Pattern.compile("((?:19|20)\\d{2})(?:[./年-](\\d{1,2}))?");

    private final JobRepository repository;
    private final JsonCodec jsonCodec;
    private final LangGraphMatchClient matchClient;
    private final AgentMatchCacheService matchCache;
    private final AhpWeightService ahpWeightService;

    public JobService(JobRepository repository, JsonCodec jsonCodec,
                      LangGraphMatchClient matchClient, AgentMatchCacheService matchCache,
                      AhpWeightService ahpWeightService) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.matchClient = matchClient;
        this.matchCache = matchCache;
        this.ahpWeightService = ahpWeightService;
    }

    /** 岗位列表的结构化筛选条件（空值表示不限）。 */
    public record JobFilter(String category, String experience, String education,
                            Integer salaryMin, String industry) {
        public static JobFilter empty() { return new JobFilter("", "", "", null, ""); }
    }

    public record MatchWeights(int skills, int role, int experience, int education, int city, int evidence) {
        public MatchWeights sanitized() {
            return new MatchWeights(
                    clampWeight(skills), clampWeight(role), clampWeight(experience),
                    clampWeight(education), clampWeight(city), clampWeight(evidence)
            );
        }

        public int total() {
            return skills + role + experience + education + city + evidence;
        }

        public AgentMatchWeights asAgentWeights() {
            return new AgentMatchWeights(skills, role, experience, education, city, evidence);
        }

        private static int clampWeight(int value) {
            return Math.max(0, Math.min(MAX_WEIGHT, value));
        }
    }

    private record SkillMatch(List<String> matched, List<String> missing, double coverage,
                              double factor, int requiredCount, boolean verificationFailure,
                              List<String> verificationNotes) {}
    private record RoleMatch(double factor, String detail) {}
    private record ExperienceMatch(double factor, String detail, boolean hardMismatch) {}
    private record EducationMatch(double factor, String detail) {}
    private record CityMatch(double factor, String detail) {}
    private record EvidenceMatch(double factor, String detail) {}
    private record ExperienceRequirement(double min, double max, boolean unlimited) {}
    private record Dimension(String key, String label, double weight, double factor, String detail) {}

    @Transactional(readOnly = true)
    public List<JobView> list(String keyword, String city) {
        return list(keyword, city, 200);
    }

    @Transactional(readOnly = true)
    public List<JobView> list(String keyword, String city, int limit) {
        String k = keyword == null ? "" : keyword.trim();
        String c = city == null ? "" : city.trim();
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return repository.searchJobs(k, c, safeLimit).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    /** 返回全库 ACTIVE 岗位的城市分布；多城市岗位会分别计入每个城市。 */
    @Transactional(readOnly = true)
    public List<CityJobCount> cityCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : repository.countActiveByCity()) {
            String raw = row[0] == null ? "" : String.valueOf(row[0]);
            long amount = row[1] instanceof Number number ? number.longValue() : 0L;
            Arrays.stream(raw.split("[,，;；/|、]+"))
                    .map(String::trim).filter(value -> !value.isBlank()).distinct()
                    .forEach(city -> counts.merge(city, amount, Long::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new CityJobCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 城市全量岗位库。与 Agent 推荐不同，这里返回该城市所有符合条件的 ACTIVE 岗位，
     * 使用规则匹配分作为浏览时的参考分，并分页避免一次渲染上千张卡片。
     */
    @Transactional(readOnly = true)
    public CityJobPage cityJobs(ResumeView resume, String city, String keyword, String sort,
                                int page, int size, JobFilter filter) {
        String targetCity = safe(city);
        if (targetCity.isBlank()) throw new BusinessException("请选择要查看的城市");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        JobFilter safeFilter = filter == null ? JobFilter.empty() : filter;
        List<JobEntity> candidates = repository.findRecommendationCandidates(targetCity).stream()
                .filter(this::isVisible)
                .filter(job -> matchesKeyword(job, keyword))
                .filter(job -> matchesFilter(job, safeFilter))
                .toList();
        Set<String> owned = normalize(resume.skills());

        List<JobRecommendation> items;
        long total;
        if ("latest".equalsIgnoreCase(safe(sort))) {
            List<JobEntity> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparing(this::latestJobTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            total = sorted.size();
            int from = Math.min(sorted.size(), safePage * safeSize);
            int to = Math.min(sorted.size(), from + safeSize);
            items = sorted.subList(from, to).stream()
                    .map(job -> score(resume, job, owned, ahpWeightsForJob(job))).toList();
        } else {
            List<JobRecommendation> scored = candidates.stream()
                    .map(job -> score(resume, job, owned, ahpWeightsForJob(job)))
                    .sorted(Comparator.comparingInt(JobRecommendation::matchScore).reversed())
                    .toList();
            total = scored.size();
            int from = Math.min(scored.size(), safePage * safeSize);
            int to = Math.min(scored.size(), from + safeSize);
            items = scored.subList(from, to);
        }
        return new CityJobPage(targetCity, total, safePage, safeSize, items);
    }

    /** 普通岗位浏览：无简历评分，返回全量 ACTIVE 查询结果的服务器分页。 */
    @Transactional(readOnly = true)
    public JobPage browse(String keyword, String city, String sort, int page, int size, JobFilter filter) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        JobFilter safeFilter = filter == null ? JobFilter.empty() : filter;
        String targetCity = safe(city);
        List<JobEntity> candidates = repository.findRecommendationCandidates(targetCity).stream()
                .filter(this::isVisible)
                .filter(job -> matchesKeyword(job, keyword))
                .filter(job -> matchesFilter(job, safeFilter))
                .sorted(Comparator.comparing(this::latestJobTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min(candidates.size(), safePage * safeSize);
        int to = Math.min(candidates.size(), from + safeSize);
        return new JobPage(safe(keyword), targetCity, candidates.size(), safePage, safeSize,
                candidates.subList(from, to).stream().map(this::toView).toList());
    }

    private boolean matchesKeyword(JobEntity job, String keyword) {
        String key = safe(keyword).toLowerCase(Locale.ROOT);
        if (key.isBlank()) return true;
        String haystack = String.join(" ", safe(job.getTitle()), safe(job.getCompany()), safe(job.getCity()),
                safe(job.getSearchText()), safe(job.getDescription()), safe(job.getJobDescription()),
                safe(job.getJobRequirements())).toLowerCase(Locale.ROOT);
        return haystack.contains(key);
    }

    private LocalDateTime latestJobTime(JobEntity job) {
        if (job.getLastSeenAt() != null) return job.getLastSeenAt();
        if (job.getCollectedAt() != null) return job.getCollectedAt();
        return job.getPublishedAt();
    }

    @Transactional(readOnly = true)
    public JobView get(Long id) {
        return toView(getEntity(id));
    }

    @Transactional(readOnly = true)
    public JobEntity getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException("岗位不存在：" + id));
    }

    @Transactional(readOnly = true)
    public List<JobRecommendation> recommend(ResumeView resume, String city, int limit) {
        return recommend(resume, city, limit, null, List.of());
    }

    @Transactional(readOnly = true)
    public List<JobRecommendation> recommend(ResumeView resume, String city, int limit,
                                              MatchWeights requestedWeights, Collection<String> excludedCities) {
        return recommend(resume, city, limit, requestedWeights, excludedCities, JobFilter.empty());
    }

    public List<JobRecommendation> recommend(ResumeView resume, String city, int limit,
                                              MatchWeights requestedWeights, Collection<String> excludedCities,
                                              JobFilter filter) {
        MatchWeights fixedWeights = requestedWeights == null ? null : requestedWeights.sanitized();
        if (fixedWeights != null && fixedWeights.total() <= 0) fixedWeights = null;
        Set<String> owned = normalize(resume.skills());
        Set<String> excluded = normalizeCityFilters(excludedCities);
        String targetCity = city == null ? "" : city.trim();
        JobFilter safeFilter = filter == null ? JobFilter.empty() : filter;
        MatchWeights finalWeights = fixedWeights;
        return repository.findRecommendationCandidates(targetCity).stream()
                .filter(this::isVisible)
                .filter(job -> targetCity.isBlank() || matchesCity(job.getCity(), targetCity))
                .filter(job -> !isExcludedCity(job.getCity(), excluded))
                .filter(job -> matchesFilter(job, safeFilter))
                .map(job -> score(resume, job, owned, finalWeights == null ? ahpWeightsForJob(job) : finalWeights))
                .sorted(Comparator.comparingInt(JobRecommendation::matchScore).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private boolean matchesFilter(JobEntity job, JobFilter filter) {
        if (!blank(filter.category()) && !filter.category().equals(job.getCategory())) return false;
        if (!blank(filter.experience()) && !contains(job.getExperience(), filter.experience())) return false;
        if (!blank(filter.education()) && !contains(job.getEducation(), filter.education())) return false;
        if (!blank(filter.industry()) && !contains(job.getIndustry(), filter.industry())) return false;
        if (filter.salaryMin() != null && filter.salaryMin() > 0) {
            Integer low = salaryLowerBound(job.getSalary());
            // 设了薪资下限时，"面议"等无法解析的岗位也排除，避免占位
            if (low == null || low < filter.salaryMin()) return false;
        }
        return true;
    }

    /**
     * 薪资是自由文本（"15-30K·15薪" / "面议"），取第一个数字作为下限。
     * 无法解析时返回 null，表示不参与薪资过滤，避免把"面议"岗位误删。
     */
    private Integer salaryLowerBound(String salary) {
        if (salary == null || salary.isBlank() || salary.contains("面议")) return null;
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(salary);
        if (!matcher.find()) return null;
        double value;
        try { value = Double.parseDouble(matcher.group(1)); } catch (NumberFormatException e) { return null; }
        // 统一换算成「K/月」，否则日薪、时薪会被误判为月薪
        if (salary.contains("元/天") || salary.contains("元/日")) return (int) Math.round(value * 22 / 1000.0);
        if (salary.contains("元/时") || salary.contains("元/小时")) return (int) Math.round(value * 8 * 22 / 1000.0);
        if (salary.contains("万/年")) return (int) Math.round(value * 10 / 12.0);
        if (salary.contains("万/月")) return (int) Math.round(value * 10);
        return (int) Math.round(value);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle.toLowerCase());
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    /**
     * Agent 精排：Agent 根据岗位实际要求给出受限权重，后端再用确定性规则因子计算分数。
     * Agent 不直接输出分数；缺少结果或权重非法时逐个岗位回退到默认规则。
     */
    @Transactional(readOnly = true)
    public AgentMatchResponse agentRerank(ResumeView resume, List<Long> jobIds) {
        if (resume == null || jobIds == null || jobIds.isEmpty()) {
            return new AgentMatchResponse(false, List.of());
        }
        List<JobEntity> entities = jobIds.stream().distinct().limit(20).map(this::getEntity).toList();
        Map<Long, AgentMatchItem> byId = new LinkedHashMap<>();
        List<JobEntity> missing = new ArrayList<>();
        for (JobEntity entity : entities) {
            AgentMatchItem cached = matchCache.get(resume, entity);
            if (cached != null) byId.put(entity.getId(), cached);
            else missing.add(entity);
        }

        if (!missing.isEmpty()) {
            List<JobView> views = missing.stream().map(this::toView).toList();
            LangGraphMatchClient.RerankResult result = matchClient.rerank(resume, views);
            if (result != null && result.available()) {
                Map<Long, LangGraphMatchClient.AgentScore> agentScores = new HashMap<>();
                for (LangGraphMatchClient.AgentScore score : result.items()) {
                    agentScores.put(score.jobId(), score);
                }
                for (JobEntity entity : missing) {
                    LangGraphMatchClient.AgentScore agent = agentScores.get(entity.getId());
                    if (agent == null) continue;
                    AgentMatchItem item = buildAgentMatch(resume, entity, agent);
                    byId.put(entity.getId(), item);
                    if ("AGENT".equals(item.scoreSource())) matchCache.put(resume, entity, item);
                }
            }
        }
        for (JobEntity entity : missing) {
            if (!byId.containsKey(entity.getId())) {
                AgentMatchItem fallback = ruleFallbackMatch(resume, entity,
                        "Agent 未返回该岗位的有效权重，使用默认规则分兜底");
                byId.put(entity.getId(), fallback);
            }
        }

        List<AgentMatchItem> items = entities.stream()
                .map(entity -> byId.get(entity.getId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean available = items.stream().anyMatch(item -> "AGENT".equals(item.scoreSource()));
        return new AgentMatchResponse(available, items);
    }

    /**
     * 单个岗位的统一岗位适配度。
     * 优先复用推荐页缓存的 Agent 结果；未命中时才调用 Agent，Agent 失败则明确标记规则兜底。
     */
    @Transactional(readOnly = true)
    public AgentMatchItem targetMatch(ResumeView resume, Long jobId) {
        if (resume == null || jobId == null) return null;
        JobEntity entity = getEntity(jobId);
        AgentMatchItem cached = matchCache.get(resume, entity);
        if (cached != null) return cached;

        LangGraphMatchClient.RerankResult result = matchClient.rerank(resume, List.of(toView(entity)));
        if (result != null && result.available()) {
            for (LangGraphMatchClient.AgentScore score : result.items()) {
                if (!java.util.Objects.equals(score.jobId(), jobId)) continue;
                AgentMatchItem item = buildAgentMatch(resume, entity, score);
                if ("AGENT".equals(item.scoreSource())) matchCache.put(resume, entity, item);
                return item;
            }
        }

        return ruleFallbackMatch(resume, entity, "Agent 不可用，使用默认规则分兜底");
    }

    private AgentMatchItem ruleFallbackMatch(ResumeView resume, JobEntity entity, String reason) {
        MatchWeights ahpWeights = ahpWeightsForJob(entity);
        JobRecommendation rule = score(resume, entity, normalize(resume.skills()), ahpWeights);
        return new AgentMatchItem(entity.getId(), rule.matchScore(), rule.matchScore(), rule.matchScore(),
                rule.matchLevel(), reason, List.of(), ahpWeights.asAgentWeights(),
                rule.scoreBreakdown(), "RULE_FALLBACK");
    }

    private AgentMatchItem buildAgentMatch(ResumeView resume, JobEntity entity,
                                           LangGraphMatchClient.AgentScore agent) {
        if (agent.weights() == null) {
            return ruleFallbackMatch(resume, entity, "Agent 未返回有效权重，使用默认规则分兜底");
        }
        JobRecommendation baseline = score(resume, entity, normalize(resume.skills()), ahpWeightsForJob(entity));
        MatchWeights adjustedWeights = weightsFromAgent(agent.weights());
        JobRecommendation adjusted = score(resume, entity, normalize(resume.skills()), adjustedWeights);
        List<MatchBreakdown> breakdown = effectiveAgentBreakdown(resume, entity, agent.breakdown());
        String reason = agent.reason() == null || agent.reason().isBlank()
                ? "Agent 已按岗位要求生成受限权重，最终分由确定性规则计算"
                : agent.reason();
        List<InterviewSkillEvidence> lowEvidence = lowInterviewEvidence(resume, entity);
        if (!lowEvidence.isEmpty()) {
            reason += "；简历技能已命中岗位要求，面试低分仅进入后续诊断："
                    + lowEvidence.stream().map(item -> item.skill() + " " + item.score() + " 分")
                    .collect(java.util.stream.Collectors.joining("、"));
        }
        return new AgentMatchItem(entity.getId(), baseline.matchScore(), adjusted.matchScore(),
                adjusted.matchScore(), adjusted.matchLevel(), reason,
                breakdown, adjustedWeights.asAgentWeights(), adjusted.scoreBreakdown(), "AGENT");
    }

    private List<MatchBreakdown> effectiveAgentBreakdown(ResumeView resume, JobEntity job,
                                                         List<LangGraphMatchClient.Breakdown> rows) {
        List<MatchBreakdown> result = new ArrayList<>();
        Set<String> declared = normalize(resume.skills());
        Set<String> required = jsonCodec.readList(job.getSkillsJson(), String.class).stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> covered = new LinkedHashSet<>();
        for (LangGraphMatchClient.Breakdown row : rows) {
            String declaredSkill = required.stream()
                    .filter(skill -> declared.contains(normalizeSkill(skill)))
                    .filter(skill -> requirementMentionsSkill(row.requirement(), skill))
                    .findFirst().orElse(null);
            if (declaredSkill != null) {
                result.add(new MatchBreakdown(row.requirement(), row.importance(), "满足",
                        skillMatchEvidence(resume, declaredSkill)));
                covered.add(normalizeSkill(declaredSkill));
            } else {
                result.add(new MatchBreakdown(row.requirement(), row.importance(), row.status(), row.evidence()));
            }
        }
        for (String skill : required) {
            if (!declared.contains(normalizeSkill(skill)) || covered.contains(normalizeSkill(skill))) continue;
            result.add(0, new MatchBreakdown("掌握 " + skill, "硬性", "满足", skillMatchEvidence(resume, skill)));
        }
        return result;
    }

    private String skillMatchEvidence(ResumeView resume, String skill) {
        InterviewSkillEvidence evidence = interviewEvidence(resume, skill);
        if (evidence == null) return "简历技能清单与岗位要求匹配，尚无面试证据";
        return "简历技能清单与岗位要求匹配；面试评分 " + evidence.score() + " 分（"
                + safe(evidence.level()) + "），仅用于后续诊断，不否定简历技能命中";
    }

    private List<InterviewSkillEvidence> lowInterviewEvidence(ResumeView resume, JobEntity job) {
        if (resume.interviewSkillEvidence() == null || resume.interviewSkillEvidence().isEmpty()) return List.of();
        Set<String> required = jsonCodec.readList(job.getSkillsJson(), String.class).stream()
                .map(this::normalizeSkill).filter(item -> !item.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return resume.interviewSkillEvidence().stream()
                .filter(item -> item != null && item.skill() != null)
                .filter(item -> required.contains(normalizeSkill(item.skill())))
                .filter(item -> item.score() < 45)
                .toList();
    }

    private InterviewSkillEvidence interviewEvidence(ResumeView resume, String skill) {
        if (resume.interviewSkillEvidence() == null) return null;
        String key = normalizeSkill(skill);
        return resume.interviewSkillEvidence().stream()
                .filter(item -> item != null && normalizeSkill(item.skill()).equals(key))
                .findFirst().orElse(null);
    }

    private boolean requirementMentionsSkill(String requirement, String skill) {
        String row = normalizeSkill(requirement);
        String key = normalizeSkill(skill);
        return !key.isBlank() && row.contains(key);
    }

    private MatchWeights weightsFromAgent(AgentMatchWeights weights) {
        if (weights == null) return asMatchWeights(ahpWeightService.baselineWeights());
        return new MatchWeights(
                bounded(weights.skills(), 20, 50),
                bounded(weights.role(), 10, 35),
                bounded(weights.experience(), 0, 30),
                bounded(weights.education(), 0, 20),
                bounded(weights.city(), 0, 15),
                bounded(weights.evidence(), 0, 25)
        );
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private MatchWeights ahpWeightsForJob(JobEntity job) {
        return asMatchWeights(ahpWeightService.weightsForJob(job));
    }

    private MatchWeights asMatchWeights(AgentMatchWeights weights) {
        return new MatchWeights(weights.skills(), weights.role(), weights.experience(),
                weights.education(), weights.city(), weights.evidence());
    }

    @Transactional(readOnly = true)
    public AhpWeightProfile ahpProfile(Long jobId) {
        return jobId == null ? ahpWeightService.profile()
                : ahpWeightService.profileForJob(getEntity(jobId));
    }

    public Map<String, Object> matchCacheStats() {
        return matchCache.stats();
    }

    @Transactional(readOnly = true)
    public List<JobView> similar(Long jobId, int limit) {
        JobEntity job = getEntity(jobId);
        String category = job.getCategory() == null ? "" : job.getCategory();
        String city = job.getCity() == null ? "" : job.getCity();
        int size = Math.max(1, Math.min(limit, 20));
        return repository.findSimilar(jobId, category, city, size).stream().map(this::toView).toList();
    }
    @Transactional(readOnly = true)
    public JobRecommendation evaluate(ResumeView resume, Long jobId) {
        JobEntity job = getEntity(jobId);
        return score(resume, job, normalize(resume.skills()), ahpWeightsForJob(job));
    }
    @Transactional
    public List<JobView> importJobs(List<JobImport> imports) {
        List<JobView> saved = new ArrayList<>();
        for (JobImport item : imports) {
            if (item == null || item.title() == null || item.title().isBlank() || item.company() == null) continue;
            String externalId = item.externalId();
            if (externalId == null || externalId.isBlank()) {
                externalId = "remote-" + Integer.toUnsignedString((item.title() + item.company()).hashCode());
            }
            JobEntity entity = repository.findByExternalId(externalId).orElseGet(JobEntity::new);
            entity.setExternalId(externalId);
            entity.setTitle(item.title());
            entity.setCompany(item.company());
            entity.setCity(item.city());
            entity.setSalary(item.salary());
            entity.setEducation(item.education());
            entity.setExperience(item.experience());
            entity.setSkillsJson(jsonCodec.write(item.skills() == null ? List.of() : item.skills()));
            entity.setResponsibilitiesJson(jsonCodec.write(item.responsibilities() == null ? List.of() : item.responsibilities()));
            entity.setDescription(item.description());
            entity.setSource(item.source() == null ? "MCP" : item.source());
            entity.setSourceUrl(item.sourceUrl());
            LocalDateTime now = LocalDateTime.now();
            entity.setCollectedAt(item.collectedAt() == null ? now : item.collectedAt());
            entity.setPublishedAt(item.publishedAt());
            entity.setValidThrough(item.validThrough());
            entity.setLastSeenAt(item.lastSeenAt() == null ? now : item.lastSeenAt());
            entity.setStatus(item.status() == null || item.status().isBlank() ? "ACTIVE" : item.status());
            entity.setContentHash(item.contentHash() == null || item.contentHash().isBlank() ? contentHash(item) : item.contentHash());
            saved.add(toView(repository.save(entity)));
        }
        return saved;
    }

    public JobView toView(JobEntity entity) {
        String title = firstNonBlank(entity.getDisplayTitle(), entity.getTitle());
        String description = firstNonBlank(entity.getDisplayDescription(), entity.getDescription());
        String jobDescription = firstNonBlank(entity.getDisplayDescription(), entity.getJobDescription());
        String requirements = firstNonBlank(entity.getDisplayRequirements(), entity.getJobRequirements());
        return new JobView(
                entity.getId(), entity.getExternalId(), title, entity.getCompany(), entity.getCity(),
                entity.getSalary(), entity.getEducation(), entity.getExperience(),
                jsonCodec.readList(entity.getSkillsJson(), String.class),
                jsonCodec.readList(entity.getResponsibilitiesJson(), String.class),
                description, jobDescription, requirements,
                entity.getContentOrigin(), entity.getAiModel(),
                entity.getAiConfidence() == null ? null : entity.getAiConfidence().toPlainString(),
                entity.getSource(), entity.getSourceUrl(), entity.getCollectedAt(), entity.getPublishedAt(),
                entity.getValidThrough(), entity.getLastSeenAt(), entity.getStatus() == null ? "ACTIVE" : entity.getStatus(),
                entity.getCategory(), entity.getIndustry(), entity.getCompanySize(), entity.getLocation()
        );
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    public Set<String> normalize(List<String> skills) {
        if (skills == null) return Set.of();
        return skills.stream().map(this::normalizeSkill).filter(skill -> !skill.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private JobRecommendation score(ResumeView resume, JobEntity job, Set<String> owned, MatchWeights weights) {
        SkillMatch skill = matchSkills(job, owned, resume);
        RoleMatch role = matchRole(resume, job);
        ExperienceMatch experience = matchExperience(resume, job);
        EducationMatch education = matchEducation(resume, job);
        CityMatch city = matchCity(resume, job);
        EvidenceMatch evidence = matchEvidence(resume);

        List<Dimension> dimensions = List.of(
                new Dimension("skills", "技能匹配", weights.skills(), skill.factor(), skillDetail(skill)),
                new Dimension("role", "岗位方向", weights.role(), role.factor(), role.detail()),
                new Dimension("experience", "工作经验", weights.experience(), experience.factor(), experience.detail()),
                new Dimension("education", "学历背景", weights.education(), education.factor(), education.detail()),
                new Dimension("city", "城市偏好", weights.city(), city.factor(), city.detail()),
                new Dimension("evidence", "项目证据", weights.evidence(), evidence.factor(), evidence.detail())
        ).stream().filter(item -> item.weight() > 0).toList();

        int totalWeight = dimensions.stream().mapToInt(item -> (int) Math.round(item.weight())).sum();
        double raw = dimensions.stream().mapToDouble(item -> item.weight() * item.factor()).sum() / totalWeight * 100.0;
        int score = (int) Math.round(raw);
        int hardCap = 100;
        List<String> capReasons = new ArrayList<>();

        // 硬性条件不受 Agent 调权影响，避免通过把某项权重调低来绕过岗位门槛。
        if (skill.requiredCount() > 0 && skill.coverage() < 0.5) {
            hardCap = Math.min(hardCap, 55);
            capReasons.add("核心技能覆盖率不足 50%");
        }
        if (role.factor() < 0.25) {
            hardCap = Math.min(hardCap, 45);
            capReasons.add("岗位方向与目标岗位差异较大");
        }
        if (experience.hardMismatch()) {
            hardCap = Math.min(hardCap, 45);
            capReasons.add("工作经验低于岗位要求");
        }
        if (!resume.educationVerified()) {
            hardCap = Math.min(hardCap, 55);
            capReasons.add("学历材料未通过真实性核验");
        } else if (education.factor() < 0.5) {
            hardCap = Math.min(hardCap, 55);
            capReasons.add("学历低于岗位要求");
        }
        if (weights.city() > 0 && city.factor() == 0) {
            hardCap = Math.min(hardCap, 75);
            capReasons.add("城市与期望城市不匹配");
        }
        if (capReasons.size() >= 2) hardCap = Math.min(hardCap, 40);
        score = Math.max(0, Math.min(100, Math.min(score, hardCap)));

        List<MatchScoreComponent> breakdown = dimensions.stream().map(item -> new MatchScoreComponent(
                item.key(), item.label(), item.weight(), round(item.factor()),
                (int) Math.round(100.0 * item.weight() * item.factor() / totalWeight), item.detail()
        )).toList();

        List<String> reasons = new ArrayList<>();
        if (!skill.matched().isEmpty()) reasons.add("已覆盖 " + joinPreview(skill.matched(), 4));
        if (!skill.missing().isEmpty()) reasons.add("建议补强 " + joinPreview(skill.missing(), 3));
        if (!skill.verificationNotes().isEmpty()) {
            reasons.add("技能匹配：" + String.join("；", skill.verificationNotes().stream().limit(3).toList()));
        }
        if (weights.role() > 0) reasons.add(role.detail());
        if (weights.experience() > 0 && experience.factor() < 0.999) reasons.add(experience.detail());
        if (weights.education() > 0 && education.factor() < 0.999) reasons.add(education.detail());
        if (weights.city() > 0) reasons.add(city.detail());
        if (weights.evidence() > 0 && evidence.factor() > 0) reasons.add(evidence.detail());
        if (!capReasons.isEmpty()) reasons.add("分数上限：" + String.join("；", capReasons));

        String level = score >= 85 ? "高度匹配" : score >= 70 ? "较为匹配" : score >= 55 ? "可以尝试" : "不建议优先";
        return new JobRecommendation(toView(job), score, level, skill.matched(), skill.missing(), reasons, breakdown);
    }
    private SkillMatch matchSkills(JobEntity job, Set<String> owned, ResumeView resume) {
        List<String> rawSkills = jsonCodec.readList(job.getSkillsJson(), String.class);
        LinkedHashMap<String, String> required = new LinkedHashMap<>();
        for (String value : rawSkills) {
            String key = normalizeSkill(value);
            if (!key.isBlank()) required.putIfAbsent(key, value.trim());
        }
        Map<String, InterviewSkillEvidence> verifiedByInterview = new LinkedHashMap<>();
        if (resume.interviewSkillEvidence() != null) {
            for (InterviewSkillEvidence item : resume.interviewSkillEvidence()) {
                if (item != null && item.skill() != null) {
                    verifiedByInterview.putIfAbsent(normalizeSkill(item.skill()), item);
                }
            }
        }

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> verificationNotes = new ArrayList<>();
        double matchedWeight = 0;
        double totalWeight = 0;
        int index = 0;
        for (Map.Entry<String, String> item : required.entrySet()) {
            double importance = index < 3 ? 1.2 : 1.0;
            totalWeight += importance;
            boolean declared = owned.contains(item.getKey());
            InterviewSkillEvidence evidence = verifiedByInterview.get(item.getKey());
            double credit;
            if (declared) {
                // 岗位推荐阶段以简历技能清单是否命中岗位要求为准。
                // 面试结果留到最终诊断中解释能力深度，不反向否定技能命中。
                credit = 1.0;
                if (evidence != null) {
                    verificationNotes.add(item.getValue() + "简历技能命中；面试评分" + evidence.score()
                            + "分（" + safe(evidence.level()) + "），仅用于后续诊断");
                } else {
                    verificationNotes.add(item.getValue() + "简历技能命中，尚无面试证据");
                }
            } else if (evidence != null && evidence.verified() && evidence.score() >= 60) {
                credit = 0.65;
                verificationNotes.add(item.getValue() + "由已验证的面试证据补充");
            } else {
                credit = 0.0;
            }
            matchedWeight += importance * credit;
            if (credit >= 0.60) matched.add(item.getValue()); else missing.add(item.getValue());
            index++;
        }
        if (required.isEmpty()) return new SkillMatch(matched, missing, 0, 0.4, 0, false, List.of());
        double coverage = totalWeight == 0 ? 0 : matchedWeight / totalWeight;
        // 岗位技能匹配只看简历技能清单对岗位要求的覆盖率，面试评分不在这里做二次折减。
        return new SkillMatch(matched, missing, coverage, clamp01(coverage), required.size(),
                false, List.copyOf(verificationNotes));
    }

    private double interviewSkillCredit(InterviewSkillEvidence evidence) {
        int score = evidence.score();
        String level = safe(evidence.level());
        // 未达到可信阈值的回答只能作为弱证据，不能和已验证技能同分。
        if (!evidence.verified()) return score >= 45 ? 0.25 : 0.0;
        if (score >= 80 || level.contains("高级")) return 1.0;
        if (score >= 60 || level.contains("中级")) return 0.65;
        if (score >= 45 || level.contains("初级")) return 0.30;
        return 0.0;
    }

    private RoleMatch matchRole(ResumeView resume, JobEntity job) {
        Set<String> targetFamilies = detectRoleFamilies(safe(resume.targetRole()), List.of());
        if (targetFamilies.isEmpty()) targetFamilies = detectRoleFamilies("", resume.skills());
        if (targetFamilies.isEmpty()) return new RoleMatch(0.4, "目标岗位信息不足，方向按中性值估算");

        List<String> jobSkills = jsonCodec.readList(job.getSkillsJson(), String.class);
        Set<String> titleFamilies = detectRoleFamilies(job.getTitle(), List.of());
        Set<String> skillFamilies = detectRoleFamilies("", jobSkills);

        // 岗位标题代表主方向，技能只作为辅助证据，避免“Android 岗位因为要求 Java 被识别成 Java 后端”。
        if (!titleFamilies.isEmpty()) {
            boolean titleMatches = targetFamilies.stream().anyMatch(titleFamilies::contains);
            if (titleMatches) {
                double factor = titleFamilies.size() > 1 ? 0.85 : 1.0;
                return new RoleMatch(factor, factor >= 1 ? "岗位主方向与目标方向一致" : "岗位为复合方向，包含目标方向");
            }
            double adjacent = adjacentRoleFactor(targetFamilies, titleFamilies);
            if (adjacent > 0) return new RoleMatch(adjacent, "岗位主方向与目标方向相邻，可迁移");
            boolean skillOverlap = targetFamilies.stream().anyMatch(skillFamilies::contains);
            if (skillOverlap) return new RoleMatch(0.20, "岗位主方向不同，仅部分技能标签重叠");
            return new RoleMatch(0, "岗位主方向与目标岗位差异较大");
        }

        Set<String> jobFamilies = new LinkedHashSet<>(titleFamilies);
        jobFamilies.addAll(skillFamilies);
        if (jobFamilies.isEmpty()) return new RoleMatch(0.5, "岗位方向标签不足，方向按中性值估算");
        boolean intersects = targetFamilies.stream().anyMatch(jobFamilies::contains);
        if (intersects) {
            double factor = jobFamilies.size() > 1 ? 0.85 : 1.0;
            return new RoleMatch(factor, factor >= 1 ? "岗位方向与目标方向一致" : "岗位包含目标方向，但同时属于复合岗位");
        }
        double adjacent = adjacentRoleFactor(targetFamilies, jobFamilies);
        if (adjacent > 0) return new RoleMatch(adjacent, "岗位方向与目标方向相邻，可迁移");
        return new RoleMatch(0, "岗位方向与目标岗位差异较大");
    }

    private ExperienceMatch matchExperience(ResumeView resume, JobEntity job) {
        double candidateYears = estimateCandidateYears(resume);
        ExperienceRequirement requirement = resolveExperienceRequirement(job);
        if (requirement.unlimited()) return new ExperienceMatch(1.0, "岗位经验要求不限", false);
        if (candidateYears + 0.01 >= requirement.min()) {
            if (requirement.max() > 0 && candidateYears > requirement.max() + 1) {
                double factor = Math.max(0.65, 1.0 - (candidateYears - requirement.max()) / Math.max(2.0, requirement.max()));
                return new ExperienceMatch(factor, "经验高于岗位区间，可能存在岗位级别错配", false);
            }
            return new ExperienceMatch(1.0, requirement.min() <= 0 ? "符合岗位经验区间" : "符合岗位最低经验要求", false);
        }
        double gap = requirement.min() - candidateYears;
        double factor = Math.max(0, 1.0 - gap / Math.max(1.0, requirement.min()));
        boolean hardMismatch = gap > 1.0;
        String structured = safe(job.getExperience());
        String source = structured.isBlank() || structured.contains("不限") ? "任职要求" : "岗位经验";
        return new ExperienceMatch(factor,
                "当前约 " + formatYears(candidateYears) + " 经验，" + source + "要求 " + formatYears(requirement.min()) + " 以上",
                hardMismatch);
    }

    private ExperienceRequirement resolveExperienceRequirement(JobEntity job) {
        ExperienceRequirement structured = parseExperienceRequirement(job.getExperience());
        if (!structured.unlimited()) return structured;
        ExperienceRequirement fromRequirements = parseExperienceRequirement(job.getJobRequirements());
        if (!fromRequirements.unlimited()) return fromRequirements;
        return structured;
    }

    private EducationMatch matchEducation(ResumeView resume, JobEntity job) {
        if (!resume.educationVerified()) {
            double factor = "MISMATCH".equals(resume.educationVerificationStatus()) ? 0.0 : 0.20;
            String detail = "MISMATCH".equals(resume.educationVerificationStatus())
                    ? "学历材料与简历不一致，真实性核验未通过，按不合格学历计"
                    : "学历材料尚未通过真实性核验，暂按不合格学历计";
            return new EducationMatch(factor, detail);
        }

        String requiredText = safe(job.getEducation());
        if (requiredText.isBlank() || requiredText.contains("不限")) {
            String requirements = safe(job.getJobRequirements());
            if (requirements.contains("博士")) requiredText = "博士";
            else if (requirements.contains("硕士")) requiredText = "硕士";
            else if (requirements.contains("本科")) requiredText = "本科";
            else if (requirements.contains("大专")) requiredText = "大专";
        }
        if (requiredText.isBlank() || requiredText.contains("不限")) {
            return new EducationMatch(1.0, "学历不限；学历材料真实性已通过");
        }
        int required = educationRank(requiredText);
        int owned = educationRank(resume.education());
        if (required <= 0) return new EducationMatch(0.75, "岗位学历字段无法识别，按中性值估算；真实性已通过");
        if (owned <= 0) return new EducationMatch(0.4, "简历学历信息不足，但材料真实性已通过");
        if (owned >= required) return new EducationMatch(1.0, "学历满足岗位要求；真实性已通过");
        if (owned == required - 1) return new EducationMatch(0.25, "学历低于岗位要求一档；真实性已通过");
        return new EducationMatch(0, "学历明显低于岗位要求；真实性已通过");
    }

    private CityMatch matchCity(ResumeView resume, JobEntity job) {
        String jobCity = safe(job.getCity());
        if (jobCity.isBlank() || containsAny(jobCity, "远程", "全国", "不限")) {
            return new CityMatch(1.0, "岗位支持远程或地点不限");
        }
        String expected = safe(resume.city());
        if (expected.isBlank()) return new CityMatch(0.5, "简历未填写期望城市，按中性值估算");
        if (matchesCity(jobCity, expected)) return new CityMatch(1.0, "城市与期望一致");
        return new CityMatch(0, "工作地与期望城市不一致");
    }

    private EvidenceMatch matchEvidence(ResumeView resume) {
        int projects = resume.projects() == null ? 0 : resume.projects().size();
        int highlighted = resume.projects() == null ? 0 : (int) resume.projects().stream()
                .filter(item -> item != null && item.highlight() != null && !item.highlight().isBlank()).count();
        int experiences = resume.experiences() == null ? 0 : resume.experiences().size();
        int achievements = resume.experiences() == null ? 0 : (int) resume.experiences().stream()
                .filter(item -> item != null && item.achievement() != null && !item.achievement().isBlank()).count();
        double factor = Math.min(1.0, projects * 0.18 + highlighted * 0.14 + experiences * 0.30 + achievements * 0.20);
        if (factor <= 0) return new EvidenceMatch(0, "尚未填写可用于验证技能的项目或经历");
        return new EvidenceMatch(factor,
                "项目 " + projects + " 个、实习/工作 " + experiences + " 段，其中量化成果 " + (highlighted + achievements) + " 条");
    }
    private Set<String> detectRoleFamilies(String text, List<String> skills) {
        String value = (safe(text) + " " + (skills == null ? "" : String.join(" ", skills))).toLowerCase(Locale.ROOT);
        Set<String> families = new LinkedHashSet<>();
        addFamily(families, value, "backend", "后端", "服务端", "java", "spring", "mybatis", "微服务", "backend", "golang", "c++");
        addFamily(families, value, "frontend", "前端", "web前端", "vue", "react", "javascript", "typescript", "html", "css");
        addFamily(families, value, "fullstack", "全栈", "full stack", "fullstack");
        addFamily(families, value, "data", "数据分析", "数据开发", "数据工程", "大数据", "etl", "spark", "hadoop", "tableau", "power bi", "bi工程师");
        addFamily(families, value, "ai", "人工智能", "机器学习", "深度学习", "算法", "大模型", "自然语言", "计算机视觉", "nlp", "llm", "pytorch", "tensorflow");
        if (containsWord(value, "ai") || containsWord(value, "cv")) families.add("ai");
        addFamily(families, value, "devops", "运维", "sre", "devops", "kubernetes", "k8s", "docker", "linux", "云原生", "系统运维");
        addFamily(families, value, "test", "测试", "qa", "质量保障", "自动化测试");
        addFamily(families, value, "security", "安全工程师", "渗透测试", "信息安全", "网络安全", "secops");
        addFamily(families, value, "mobile", "android", "安卓", "鸿蒙", "ios", "移动端", "客户端开发", "kotlin", "flutter", "react native");
        addFamily(families, value, "embedded", "嵌入式", "单片机", "驱动开发", "硬件工程师");
        addFamily(families, value, "game", "游戏开发", "unity", "unreal", "ue4", "ue5");
        addFamily(families, value, "product", "产品经理", "产品设计", "产品开发");
        return families;
    }

    private void addFamily(Set<String> families, String value, String family, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                families.add(family);
                return;
            }
        }
    }

    private double adjacentRoleFactor(Set<String> left, Set<String> right) {
        double best = 0;
        for (String a : left) {
            for (String b : right) best = Math.max(best, relatedRoleFactor(a, b));
        }
        return best;
    }

    private double relatedRoleFactor(String a, String b) {
        if (a.equals(b)) return 1.0;
        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        return switch (key) {
            case "backend|fullstack", "frontend|fullstack" -> 0.65;
            case "ai|data" -> 0.55;
            case "mobile|frontend" -> 0.45;
            case "backend|devops" -> 0.35;
            case "backend|test", "frontend|test", "devops|security", "backend|product" -> 0.35;
            case "ai|backend" -> 0.30;
            case "frontend|game", "backend|game" -> 0.30;
            case "data|devops", "data|backend" -> 0.20;
            default -> 0;
        };
    }

    private ExperienceRequirement parseExperienceRequirement(String value) {
        String text = safe(value).trim();
        if (text.isBlank()) return new ExperienceRequirement(0, 1, true);
        Matcher range = EXPERIENCE_RANGE.matcher(text);
        if (range.find()) return new ExperienceRequirement(parseDouble(range.group(1)), parseDouble(range.group(2)), false);
        Matcher above = EXPERIENCE_ABOVE.matcher(text);
        if (above.find()) return new ExperienceRequirement(parseDouble(above.group(1)), -1, false);
        Matcher below = EXPERIENCE_BELOW.matcher(text);
        if (below.find()) return new ExperienceRequirement(0, parseDouble(below.group(1)), false);
        if (containsAny(text, "不限", "无要求", "应届", "在校", "实习")) {
            return new ExperienceRequirement(0, 1, true);
        }
        return new ExperienceRequirement(0, -1, true);
    }

    private double estimateCandidateYears(ResumeView resume) {
        if (resume.experiences() == null || resume.experiences().isEmpty()) return 0;
        int months = 0;
        int fallbackMonths = 0;
        for (ExperienceItem item : resume.experiences()) {
            if (item == null) continue;
            int periodMonths = periodMonths(item.period());
            if (periodMonths > 0) months += periodMonths;
            else if ((item.company() != null && !item.company().isBlank())
                    || (item.position() != null && !item.position().isBlank())
                    || (item.description() != null && !item.description().isBlank())) fallbackMonths += 6;
        }
        if (months == 0) months = fallbackMonths;
        return Math.min(20.0, months / 12.0);
    }

    private int periodMonths(String period) {
        if (period == null || period.isBlank()) return 0;
        Matcher matcher = PERIOD_DATE.matcher(period);
        List<LocalDate> dates = new ArrayList<>();
        while (matcher.find() && dates.size() < 2) {
            int year = Integer.parseInt(matcher.group(1));
            int month = matcher.group(2) == null ? 1 : Math.max(1, Math.min(12, Integer.parseInt(matcher.group(2))));
            dates.add(LocalDate.of(year, month, 1));
        }
        if (dates.size() < 2) return 0;
        return (int) Math.abs(ChronoUnit.MONTHS.between(dates.get(0), dates.get(1)));
    }

    private int educationRank(String value) {
        String text = safe(value).replace("学历", "").trim();
        if (text.isBlank() || text.contains("不限")) return 0;
        if (text.contains("博士")) return 6;
        if (text.contains("硕士") || text.contains("研究生")) return 5;
        if (text.contains("本科") || text.contains("学士")) return 4;
        if (text.contains("大专") || text.contains("专科")) return 3;
        if (text.contains("高中") || text.contains("中专")) return 2;
        if (text.contains("初中")) return 1;
        return 0;
    }

    private Set<String> normalizeCityFilters(Collection<String> cities) {
        if (cities == null) return Set.of();
        return cities.stream().map(this::safe).map(String::trim).filter(city -> !city.isBlank())
                .map(city -> city.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isExcludedCity(String city, Set<String> excluded) {
        String normalized = safe(city).toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && excluded.stream().anyMatch(item -> normalized.equals(item) || normalized.contains(item));
    }

    private boolean matchesCity(String jobCity, String expectedCity) {
        String job = safe(jobCity).toLowerCase(Locale.ROOT);
        String expected = safe(expectedCity).toLowerCase(Locale.ROOT);
        return !job.isBlank() && !expected.isBlank() && (job.equals(expected) || job.contains(expected) || expected.contains(job));
    }

    private String normalizeSkill(String value) {
        if (value == null) return "";
        String skill = value.trim().toLowerCase(Locale.ROOT).replace(".", "").replace(" ", "");
        return switch (skill) {
            case "springboot" -> "spring boot";
            case "jdk", "java8", "java17" -> "java";
            case "postgresql", "pgsql" -> "postgresql";
            case "springcloud" -> "spring cloud";
            case "langchain4j", "langchain" -> "langchain";
            case "pytorch", "torch" -> "pytorch";
            case "vuejs" -> "vue";
            case "reactjs" -> "react";
            default -> skill;
        };
    }
    private String skillDetail(SkillMatch skill) {
        if (skill.requiredCount() == 0) return "岗位缺少结构化技能标签，按中性值估算";
        String detail = "覆盖 " + skill.matched().size() + "/" + skill.requiredCount() + " 项岗位技能";
        if (!skill.verificationNotes().isEmpty()) {
            detail += "；" + String.join("；", skill.verificationNotes().stream().limit(3).toList());
        }
        return detail;
    }

    private String joinPreview(List<String> values, int limit) {
        return String.join("、", values.subList(0, Math.min(limit, values.size())));
    }

    private boolean containsWord(String value, String word) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(word) + "(?![a-z0-9])").matcher(value).find();
    }

    private boolean containsAny(String value, String... terms) {
        if (value == null) return false;
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value); } catch (Exception ignored) { return 0; }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String formatYears(double years) {
        return years <= 0.01 ? "无正式工作经验" : String.format(Locale.ROOT, "%.1f 年", years);
    }

    private boolean isVisible(JobEntity job) {
        return "ACTIVE".equalsIgnoreCase(job.getStatus())
                && (job.getValidThrough() == null || !job.getValidThrough().isBefore(LocalDateTime.now()));
    }

    private String contentHash(JobImport item) {
        String value = String.join("|", safe(item.title()), safe(item.company()), safe(item.city()), safe(item.salary()), safe(item.description()));
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}