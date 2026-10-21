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

    public JobService(JobRepository repository, JsonCodec jsonCodec, LangGraphMatchClient matchClient) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.matchClient = matchClient;
    }

    /** 岗位列表的结构化筛选条件（空值表示不限）。 */
    public record JobFilter(String category, String experience, String education,
                            Integer salaryMin, String industry) {
        public static JobFilter empty() { return new JobFilter("", "", "", null, ""); }
    }

    public record MatchWeights(int skills, int role, int experience, int education, int city, int evidence) {
        public static MatchWeights defaults() {
            return new MatchWeights(35, 20, 15, 10, 5, 5);
        }

        public MatchWeights sanitized() {
            return new MatchWeights(
                    clampWeight(skills), clampWeight(role), clampWeight(experience),
                    clampWeight(education), clampWeight(city), clampWeight(evidence)
            );
        }

        public int total() {
            return skills + role + experience + education + city + evidence;
        }

        private static int clampWeight(int value) {
            return Math.max(0, Math.min(MAX_WEIGHT, value));
        }
    }

    private record SkillMatch(List<String> matched, List<String> missing, double coverage,
                              double factor, int requiredCount) {}
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
        return recommend(resume, city, limit, MatchWeights.defaults(), List.of());
    }

    @Transactional(readOnly = true)
    public List<JobRecommendation> recommend(ResumeView resume, String city, int limit,
                                              MatchWeights requestedWeights, Collection<String> excludedCities) {
        return recommend(resume, city, limit, requestedWeights, excludedCities, JobFilter.empty());
    }

    public List<JobRecommendation> recommend(ResumeView resume, String city, int limit,
                                              MatchWeights requestedWeights, Collection<String> excludedCities,
                                              JobFilter filter) {
        MatchWeights weights = requestedWeights == null ? MatchWeights.defaults() : requestedWeights.sanitized();
        if (weights.total() <= 0) weights = MatchWeights.defaults();
        Set<String> owned = normalize(resume.skills());
        Set<String> excluded = normalizeCityFilters(excludedCities);
        String targetCity = city == null ? "" : city.trim();
        JobFilter safeFilter = filter == null ? JobFilter.empty() : filter;
        MatchWeights finalWeights = weights;
        return repository.findRecommendationCandidates(targetCity).stream()
                .filter(this::isVisible)
                .filter(job -> targetCity.isBlank() || matchesCity(job.getCity(), targetCity))
                .filter(job -> !isExcludedCity(job.getCity(), excluded))
                .filter(job -> matchesFilter(job, safeFilter))
                .map(job -> score(resume, job, owned, finalWeights))
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
     * Agent 精排：对给定岗位按「岗位自身要求」打分，并与规则分融合。
     * 最终分 = Agent 0.7 + 规则 0.3；Agent 不可用时返回 available=false。
     */
    @Transactional(readOnly = true)
    public AgentMatchResponse agentRerank(ResumeView resume, List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return new AgentMatchResponse(false, List.of());
        List<JobView> jobs = jobIds.stream().distinct().limit(20).map(this::get).toList();
        LangGraphMatchClient.RerankResult result = matchClient.rerank(resume, jobs);
        if (result == null || !result.available()) return new AgentMatchResponse(false, List.of());

        Map<Long, LangGraphMatchClient.AgentScore> byId = new HashMap<>();
        for (LangGraphMatchClient.AgentScore item : result.items()) byId.put(item.jobId(), item);

        List<AgentMatchItem> items = new ArrayList<>();
        for (JobView job : jobs) {
            LangGraphMatchClient.AgentScore agent = byId.get(job.id());
            if (agent == null) continue;
            int rule = evaluate(resume, job.id()).matchScore();
            int fused = (int) Math.round(agent.score() * 0.7 + rule * 0.3);
            List<MatchBreakdown> breakdown = agent.breakdown().stream()
                    .map(row -> new MatchBreakdown(row.requirement(), row.importance(), row.status(), row.evidence()))
                    .toList();
            items.add(new AgentMatchItem(job.id(), rule, agent.score(), fused,
                    agent.verdict(), agent.reason(), breakdown));
        }
        return new AgentMatchResponse(!items.isEmpty(), items);
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
        return score(resume, job, normalize(resume.skills()), MatchWeights.defaults());
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
        return new JobView(
                entity.getId(), entity.getExternalId(), entity.getTitle(), entity.getCompany(), entity.getCity(),
                entity.getSalary(), entity.getEducation(), entity.getExperience(),
                jsonCodec.readList(entity.getSkillsJson(), String.class),
                jsonCodec.readList(entity.getResponsibilitiesJson(), String.class),
                entity.getDescription(), entity.getJobDescription(), entity.getJobRequirements(),
                entity.getContentOrigin(), entity.getAiModel(),
                entity.getAiConfidence() == null ? null : entity.getAiConfidence().toPlainString(),
                entity.getSource(), entity.getSourceUrl(), entity.getCollectedAt(), entity.getPublishedAt(),
                entity.getValidThrough(), entity.getLastSeenAt(), entity.getStatus() == null ? "ACTIVE" : entity.getStatus(),
                entity.getCategory(), entity.getIndustry(), entity.getCompanySize(), entity.getLocation()
        );
    }

    public Set<String> normalize(List<String> skills) {
        if (skills == null) return Set.of();
        return skills.stream().map(this::normalizeSkill).filter(skill -> !skill.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private JobRecommendation score(ResumeView resume, JobEntity job, Set<String> owned, MatchWeights weights) {
        SkillMatch skill = matchSkills(job, owned);
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

        if (weights.skills() > 0 && skill.requiredCount() > 0 && skill.coverage() < 0.5) {
            hardCap = Math.min(hardCap, 69);
            capReasons.add("核心技能覆盖率不足 50%");
        }
        if (weights.role() > 0 && role.factor() < 0.25) {
            hardCap = Math.min(hardCap, 70);
            capReasons.add("岗位方向与目标岗位差异较大");
        }
        if (weights.experience() > 0 && experience.hardMismatch()) {
            hardCap = Math.min(hardCap, 65);
            capReasons.add("工作经验低于岗位要求");
        }
        if (weights.education() > 0 && education.factor() < 0.5) {
            hardCap = Math.min(hardCap, 70);
            capReasons.add("学历低于岗位要求");
        }
        if (weights.city() > 0 && city.factor() == 0) {
            hardCap = Math.min(hardCap, 75);
            capReasons.add("城市与期望城市不匹配");
        }
        score = Math.max(0, Math.min(100, Math.min(score, hardCap)));

        List<MatchScoreComponent> breakdown = dimensions.stream().map(item -> new MatchScoreComponent(
                item.key(), item.label(), item.weight(), round(item.factor()),
                (int) Math.round(100.0 * item.weight() * item.factor() / totalWeight), item.detail()
        )).toList();

        List<String> reasons = new ArrayList<>();
        if (!skill.matched().isEmpty()) reasons.add("已覆盖 " + joinPreview(skill.matched(), 4));
        if (!skill.missing().isEmpty()) reasons.add("建议补强 " + joinPreview(skill.missing(), 3));
        if (weights.role() > 0) reasons.add(role.detail());
        if (weights.experience() > 0 && experience.factor() < 0.999) reasons.add(experience.detail());
        if (weights.education() > 0 && education.factor() < 0.999) reasons.add(education.detail());
        if (weights.city() > 0) reasons.add(city.detail());
        if (weights.evidence() > 0 && evidence.factor() > 0) reasons.add(evidence.detail());
        if (!capReasons.isEmpty()) reasons.add("分数上限：" + String.join("；", capReasons));

        String level = score >= 85 ? "高度匹配" : score >= 70 ? "较为匹配" : score >= 55 ? "可以尝试" : "不建议优先";
        return new JobRecommendation(toView(job), score, level, skill.matched(), skill.missing(), reasons, breakdown);
    }
    private SkillMatch matchSkills(JobEntity job, Set<String> owned) {
        List<String> rawSkills = jsonCodec.readList(job.getSkillsJson(), String.class);
        LinkedHashMap<String, String> required = new LinkedHashMap<>();
        for (String value : rawSkills) {
            String key = normalizeSkill(value);
            if (!key.isBlank()) required.putIfAbsent(key, value.trim());
        }
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        double matchedWeight = 0;
        double totalWeight = 0;
        int index = 0;
        for (Map.Entry<String, String> item : required.entrySet()) {
            double importance = index < 3 ? 1.2 : 1.0;
            totalWeight += importance;
            if (owned.contains(item.getKey())) {
                matchedWeight += importance;
                matched.add(item.getValue());
            } else {
                missing.add(item.getValue());
            }
            index++;
        }
        if (required.isEmpty()) return new SkillMatch(matched, missing, 0, 0.4, 0);
        double coverage = totalWeight == 0 ? 0 : matchedWeight / totalWeight;
        double confidence = Math.min(1.0, 0.65 + required.size() * 0.12);
        return new SkillMatch(matched, missing, coverage, clamp01(coverage * confidence), required.size());
    }

    private RoleMatch matchRole(ResumeView resume, JobEntity job) {
        Set<String> targetFamilies = detectRoleFamilies(safe(resume.targetRole()), List.of());
        if (targetFamilies.isEmpty()) targetFamilies = detectRoleFamilies("", resume.skills());
        Set<String> jobFamilies = detectRoleFamilies(job.getTitle(), jsonCodec.readList(job.getSkillsJson(), String.class));
        if (targetFamilies.isEmpty()) return new RoleMatch(0.4, "目标岗位信息不足，方向按中性值估算");
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
        ExperienceRequirement requirement = parseExperienceRequirement(job.getExperience());
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
        return new ExperienceMatch(factor,
                "当前约 " + formatYears(candidateYears) + " 经验，岗位要求 " + safe(job.getExperience()),
                hardMismatch);
    }

    private EducationMatch matchEducation(ResumeView resume, JobEntity job) {
        String requiredText = safe(job.getEducation());
        if (requiredText.isBlank() || requiredText.contains("不限")) return new EducationMatch(1.0, "学历不限");
        int required = educationRank(requiredText);
        int owned = educationRank(resume.education());
        if (required <= 0) return new EducationMatch(0.75, "岗位学历字段无法识别，按中性值估算");
        if (owned <= 0) return new EducationMatch(0.4, "简历学历信息不足");
        if (owned >= required) return new EducationMatch(1.0, "学历满足岗位要求");
        if (owned == required - 1) return new EducationMatch(0.25, "学历低于岗位要求一档");
        return new EducationMatch(0, "学历明显低于岗位要求");
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
        addFamily(families, value, "mobile", "android", "ios", "移动端", "客户端开发");
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
        if (text.isBlank() || containsAny(text, "不限", "无要求", "应届", "在校", "实习")) {
            return new ExperienceRequirement(0, 1, true);
        }
        Matcher range = EXPERIENCE_RANGE.matcher(text);
        if (range.find()) return new ExperienceRequirement(parseDouble(range.group(1)), parseDouble(range.group(2)), false);
        Matcher above = EXPERIENCE_ABOVE.matcher(text);
        if (above.find()) return new ExperienceRequirement(parseDouble(above.group(1)), -1, false);
        Matcher below = EXPERIENCE_BELOW.matcher(text);
        if (below.find()) return new ExperienceRequirement(0, parseDouble(below.group(1)), false);
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
        return "覆盖 " + skill.matched().size() + "/" + skill.requiredCount() + " 项岗位技能";
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