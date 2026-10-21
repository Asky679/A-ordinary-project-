package com.careernav.service;

import com.careernav.domain.ApiModels.*;
import com.careernav.entity.DiagnosisReportEntity;
import com.careernav.repository.DiagnosisReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DiagnosisService {
    private static final Pattern EVIDENCE = Pattern.compile("\\d+\\s*(%|万|千|ms|s|qps|tps|人|个|次|倍)|提升|降低|优化|并发|准确率|覆盖率", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_WORD = Pattern.compile("负责|主导|设计|实现|优化|推动|构建");

    private final DiagnosisReportRepository repository;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final JsonCodec jsonCodec;
    private final AgentModelClient agentModelClient;
    private final LearningPathService learningPathService;
    private final LangGraphDiagnosisClient diagnosisClient;

    public DiagnosisService(DiagnosisReportRepository repository, ResumeService resumeService, JobService jobService,
                            JsonCodec jsonCodec, AgentModelClient agentModelClient,
                            LearningPathService learningPathService,
                            LangGraphDiagnosisClient diagnosisClient) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
        this.agentModelClient = agentModelClient;
        this.learningPathService = learningPathService;
        this.diagnosisClient = diagnosisClient;
    }

    /**
     * Agent 优先的诊断：先算确定性规则分，再让 LangGraph Agent 自主调用工具补充证据，
     * 最后把 Agent 的建议合并回报告。Agent 不可用时自动退回纯规则诊断。
     */
    @Transactional
    public DiagnosisReport diagnose(Long resumeId, Long jobId) {
        DiagnosisReport base = diagnoseRuleBased(resumeId, jobId);
        if (diagnosisClient == null || !diagnosisClient.available()) return base;
        LangGraphDiagnosisClient.Enrichment enrichment = diagnosisClient.analyze(resumeId, jobId);
        if (enrichment == null) return base;
        return applyAgentEnrichment(base, enrichment);
    }

    private DiagnosisReport applyAgentEnrichment(DiagnosisReport base, LangGraphDiagnosisClient.Enrichment enrichment) {
        List<String> strengths = new ArrayList<>(base.strengths());
        for (String value : enrichment.strengths()) {
            if (value != null && !value.isBlank() && strengths.size() < 8 && !strengths.contains(value)) {
                strengths.add(clip(value, 180));
            }
        }

        List<DiagnosisSuggestion> suggestions = new ArrayList<>();
        for (LangGraphDiagnosisClient.Suggestion value : enrichment.suggestions()) {
            if (suggestions.size() >= 8) break;
            suggestions.add(new DiagnosisSuggestion(
                    normalizePriority(value.priority()),
                    safeText(value.category(), "Agent 建议"),
                    safeText(value.issue(), "发现新的优化点"),
                    safeText(value.action(), "结合岗位要求继续修改简历"),
                    safeText(value.example(), "补充真实、可验证的项目结果")));
        }
        if (suggestions.isEmpty()) suggestions.addAll(base.suggestions());

        String summary = enrichment.summary() == null || enrichment.summary().isBlank()
                ? base.summary() : clip(enrichment.summary(), 400);

        DiagnosisReport enriched = new DiagnosisReport(base.id(), base.resumeId(), base.jobId(), base.targetRole(),
                base.overallScore(), base.resumeQualityScore(), base.jobMatchScore(), base.jobMatchLevel(),
                base.level(), summary, base.dimensions(), base.jobMatchBreakdown(), strengths,
                base.matchedKeywords(), base.missingKeywords(), suggestions,
                enrichment.toolCalls(), base.learningPlan(), base.resumeUpdatedAt(), base.createdAt());

        repository.findById(base.id()).ifPresent(entity -> {
            entity.setReportJson(jsonCodec.write(enriched));
            repository.save(entity);
        });
        return enriched;
    }

    @Transactional
    public DiagnosisReport diagnoseRuleBased(Long resumeId, Long jobId) {
        return diagnoseRuleInternal(resumeId, jobId, true);
    }

    /** 只计算、不落库，供诊断 Agent 作为工具调用，避免产生额外的诊断记录。 */
    @Transactional(readOnly = true)
    public DiagnosisReport previewRuleBased(Long resumeId, Long jobId) {
        return diagnoseRuleInternal(resumeId, jobId, false);
    }

    private DiagnosisReport diagnoseRuleInternal(Long resumeId, Long jobId, boolean persist) {
        ResumeView resume = resumeService.get(resumeId);
        JobView job = jobId == null ? null : jobService.get(jobId);
        JobRecommendation jobMatch = jobId == null ? null : jobService.evaluate(resume, jobId);
        String targetRole = job == null ? resume.targetRole() : job.title();

        int completeness = completeness(resume);
        int achievement = achievement(resume);
        int depth = depth(resume);
        int expression = expression(resume, jobMatch == null ? Set.of() : new LinkedHashSet<>(jobMatch.matchedSkills()));
        int education = education(resume, job);
        int credibility = credibility(resume);

        List<DiagnosisDimension> qualityDimensions = List.of(
                new DiagnosisDimension("内容完整性", completeness, 20, comment(completeness, "基础信息、教育、技能与项目字段")),
                new DiagnosisDimension("成果量化", achievement, 20, achievement >= 75 ? "项目成果有明确指标" : "缺少可验证的规模、性能或业务结果"),
                new DiagnosisDimension("技术深度", depth, 15, depth >= 75 ? "技能与项目能够相互支撑" : "技能数量较多，但项目证据不足"),
                new DiagnosisDimension("表达与ATS", expression, 20, expression >= 75 ? "关键词与行动表达较清晰" : "关键词密度或行动表达仍有提升空间"),
                new DiagnosisDimension("教育基础", education, 10, "学历、学校与专业背景"),
                new DiagnosisDimension("项目可信度", credibility, 15, credibility >= 75 ? "项目与成果能够验证技能" : "项目或实习证据不足")
        );
        int resumeQualityScore = weightedScore(qualityDimensions);
        Integer jobMatchScore = jobMatch == null ? null : jobMatch.matchScore();
        int overallScore = jobMatchScore == null ? resumeQualityScore : (int) Math.round(jobMatchScore * 0.6 + resumeQualityScore * 0.4);
        String level = level(overallScore);
        String summary = buildSummary(targetRole, resumeQualityScore, jobMatch, qualityDimensions);

        Set<String> matched = jobMatch == null ? Set.of() : new LinkedHashSet<>(jobMatch.matchedSkills());
        Set<String> missing = jobMatch == null ? Set.of() : new LinkedHashSet<>(jobMatch.missingSkills());
        // buildStrengths() returns an immutable list (Stream.toList()); the Agent enrichment
        // below appends to it, so wrap it in a mutable list first.
        List<String> strengths = new ArrayList<>(buildStrengths(qualityDimensions, jobMatch));
        List<DiagnosisSuggestion> suggestions = new ArrayList<>(buildSuggestions(resume, qualityDimensions, matched, missing, jobMatch));

        LearningPath learningPlan = learningPathService.generate(resumeId, targetRole);

        AiDiagnosis aiDiagnosis = enrichWithAgent(resume, targetRole, qualityDimensions, jobMatch, summary, suggestions, strengths);
        if (aiDiagnosis != null) {
            if (aiDiagnosis.strengths() != null) {
                aiDiagnosis.strengths().stream().filter(Objects::nonNull).filter(value -> !value.isBlank())
                        .forEach(value -> { if (strengths.size() < 8 && !strengths.contains(value)) strengths.add(clip(value, 180)); });
            }
            if (aiDiagnosis.suggestions() != null) {
                aiDiagnosis.suggestions().stream().filter(Objects::nonNull)
                        .map(value -> new DiagnosisSuggestion(normalizePriority(value.priority()), safeText(value.category(), "Agent 建议"),
                                safeText(value.issue(), "发现新的优化点"), safeText(value.action(), "结合岗位要求继续修改简历"),
                                safeText(value.example(), "补充真实、可验证的项目结果")))
                        .forEach(value -> { if (suggestions.size() < 8) suggestions.add(value); });
            }
        }

        DiagnosisReport draft = new DiagnosisReport(null, resume.id(), jobId, targetRole, overallScore,
                resumeQualityScore, jobMatchScore, jobMatch == null ? "未选择岗位" : jobMatch.matchLevel(), level, summary,
                qualityDimensions, jobMatch == null ? List.of() : jobMatch.scoreBreakdown(), strengths,
                List.copyOf(matched), List.copyOf(missing), suggestions, List.of(), learningPlan,
                resume.updatedAt(), LocalDateTime.now());
        if (!persist) return draft;

        DiagnosisReportEntity entity = new DiagnosisReportEntity();
        entity.setResumeId(resume.id());
        entity.setJobId(jobId);
        entity.setTargetRole(targetRole);
        entity.setOverallScore(overallScore);
        entity.setResumeQualityScore(resumeQualityScore);
        entity.setJobMatchScore(jobMatchScore);
        entity.setReportJson(jsonCodec.write(draft));
        entity.setCreatedAt(draft.createdAt());
        entity = repository.save(entity);

        DiagnosisReport report = new DiagnosisReport(entity.getId(), draft.resumeId(), draft.jobId(), draft.targetRole(),
                draft.overallScore(), draft.resumeQualityScore(), draft.jobMatchScore(), draft.jobMatchLevel(),
                draft.level(), draft.summary(), draft.dimensions(), draft.jobMatchBreakdown(), draft.strengths(),
                draft.matchedKeywords(), draft.missingKeywords(), draft.suggestions(), draft.agentTrace(), draft.learningPlan(),
                draft.resumeUpdatedAt(), draft.createdAt());
        entity.setReportJson(jsonCodec.write(report));
        repository.save(entity);
        return report;
    }

    @Transactional(readOnly = true)
    public DiagnosisReport latest(Long resumeId) {
        return repository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId).map(entity -> {
            DiagnosisReport report = jsonCodec.read(entity.getReportJson(), DiagnosisReport.class);
            return isCurrent(report) ? report : null;
        }).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<DiagnosisReport> list(Long resumeId) {
        return repository.findAll().stream()
                .filter(entity -> resumeId == null || resumeId.equals(entity.getResumeId()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(entity -> jsonCodec.read(entity.getReportJson(), DiagnosisReport.class))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public int averageScore() {
        return (int) Math.round(repository.findAll().stream()
                .map(entity -> jsonCodec.read(entity.getReportJson(), DiagnosisReport.class))
                .filter(this::isCurrent)
                .mapToInt(DiagnosisReport::overallScore)
                .average().orElse(0));
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    public Set<String> fallbackSkills(String role) {
        String value = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (value.contains("前端") || value.contains("front")) return new LinkedHashSet<>(List.of("html", "css", "javascript", "vue", "react", "typescript", "webpack"));
        if (value.contains("算法") || value.contains("ai") || value.contains("人工智能")) return new LinkedHashSet<>(List.of("python", "pytorch", "langchain", "rag", "mcp", "docker"));
        return new LinkedHashSet<>(List.of("java", "spring boot", "mysql", "redis", "spring cloud", "docker"));
    }
    private int weightedScore(List<DiagnosisDimension> dimensions) {
        return (int) Math.round(dimensions.stream().mapToDouble(item -> item.score() * item.weight() / 100.0).sum());
    }

    private int completeness(ResumeView resume) {
        int score = 30;
        if (nonBlank(resume.phone())) score += 8;
        if (nonBlank(resume.email())) score += 7;
        if (nonBlank(resume.education())) score += 10;
        if (nonBlank(resume.school())) score += 8;
        if (!safe(resume.skills()).isEmpty()) score += 12;
        if (!safe(resume.projects()).isEmpty()) score += 15;
        if (nonBlank(resume.selfEvaluation())) score += 10;
        return Math.min(100, score);
    }

    private int achievement(ResumeView resume) {
        String text = safe(resume.projects()).stream().map(item -> n(item.description()) + " " + n(item.highlight())).collect(Collectors.joining(" "))
                + " " + safe(resume.experiences()).stream().map(item -> n(item.description()) + " " + n(item.achievement())).collect(Collectors.joining(" "));
        long evidenceCount = EVIDENCE.matcher(text).results().count();
        long highlighted = safe(resume.projects()).stream().filter(item -> nonBlank(item.highlight())).count();
        long achievements = safe(resume.experiences()).stream().filter(item -> nonBlank(item.achievement())).count();
        return Math.min(100, 25 + (int) evidenceCount * 10 + (int) highlighted * 5 + (int) achievements * 5);
    }

    private int depth(ResumeView resume) {
        int skillScore = Math.min(35, safe(resume.skills()).size() * 5 / 2);
        int projectScore = Math.min(25, safe(resume.projects()).size() * 15);
        long highlighted = safe(resume.projects()).stream().filter(item -> nonBlank(item.highlight())).count();
        int highlightScore = Math.min(20, (int) highlighted * 10);
        return Math.min(100, 20 + skillScore + projectScore + highlightScore);
    }

    private int expression(ResumeView resume, Set<String> matched) {
        String text = n(resume.selfEvaluation()) + " " + n(resume.rawText());
        int score = 45;
        if (text.length() >= 80) score += 10;
        if (text.length() >= 220) score += 6;
        if (ACTION_WORD.matcher(text).find()) score += 12;
        score += Math.min(18, matched.size() * 3);
        return Math.min(100, score);
    }

    private int education(ResumeView resume, JobView job) {
        int score = 65;
        if (nonBlank(resume.education())) score += 12;
        if (nonBlank(resume.school())) score += 10;
        if (nonBlank(resume.major()) && resume.major().matches(".*(计算机|软件|信息|数据|人工智能|网络).*")) score += 13;
        if (job != null && job.education() != null && job.education().contains("硕士") && "本科".equals(resume.education())) score -= 12;
        return Math.max(45, Math.min(100, score));
    }

    private int credibility(ResumeView resume) {
        long highlighted = safe(resume.projects()).stream().filter(item -> nonBlank(item.highlight())).count();
        long achievements = safe(resume.experiences()).stream().filter(item -> nonBlank(item.achievement())).count();
        int score = 15 + safe(resume.projects()).size() * 20 + safe(resume.experiences()).size() * 20
                + (int) highlighted * 12 + (int) achievements * 8;
        return Math.min(100, score);
    }

    private String buildSummary(String targetRole, int qualityScore, JobRecommendation match, List<DiagnosisDimension> dimensions) {
        String focus = dimensions.stream().sorted(Comparator.comparingInt(DiagnosisDimension::score))
                .limit(3).map(DiagnosisDimension::name).collect(Collectors.joining("、"));
        if (match == null) return String.format("简历质量得分 %d 分。主要受影响维度是%s。当前未选择具体岗位，因此不计算岗位匹配分。", qualityScore, focus);
        String matchFocus = match.scoreBreakdown().stream().filter(item -> item.weight() > 0)
                .sorted(Comparator.comparingDouble(MatchScoreComponent::factor)).limit(3)
                .map(MatchScoreComponent::label).collect(Collectors.joining("、"));
        int overall = (int) Math.round(match.matchScore() * 0.6 + qualityScore * 0.4);
        return String.format("综合分 %d = 岗位匹配 %d × 60%% + 简历质量 %d × 40%%。简历质量主要失分在%s；与“%s”的岗位匹配主要受%s影响。",
                overall, match.matchScore(), qualityScore, focus, targetRole, matchFocus.isBlank() ? "岗位整体要求" : matchFocus);
    }

    private List<String> buildStrengths(List<DiagnosisDimension> dimensions, JobRecommendation match) {
        List<String> values = new ArrayList<>();
        dimensions.stream().filter(item -> item.score() >= 80).forEach(item -> values.add(item.name() + "表现较好：" + item.comment()));
        if (match != null) {
            if (match.matchScore() >= 75) values.add("岗位匹配度较高：" + match.matchLevel());
            if (!match.matchedSkills().isEmpty()) values.add("已覆盖岗位关键词：" + String.join("、", match.matchedSkills()));
        }
        if (values.isEmpty()) values.add("简历已有基础信息，建议先补齐项目证据和量化成果");
        return values.stream().distinct().limit(8).toList();
    }

    private List<DiagnosisSuggestion> buildSuggestions(ResumeView resume, List<DiagnosisDimension> dimensions,
                                                       Set<String> matched, Set<String> missing, JobRecommendation match) {
        List<DiagnosisSuggestion> values = new ArrayList<>();
        int credibility = dimensionScore(dimensions, "项目可信度");
        int achievement = dimensionScore(dimensions, "成果量化");
        int depth = dimensionScore(dimensions, "技术深度");
        int expression = dimensionScore(dimensions, "表达与ATS");

        if (credibility < 75 || safe(resume.projects()).isEmpty()) {
            values.add(new DiagnosisSuggestion("P0", "项目证据", "项目或实习证据不足，技能无法被招聘方验证",
                    "补充 1-2 个真实项目，明确个人职责、技术难点和可验证结果", "使用 Spring Boot 完成后端服务，接口 P95 从 620ms 降至 180ms"));
        }
        if (achievement < 75) {
            values.add(new DiagnosisSuggestion("P0", "成果量化", "项目描述偏职责，缺少规模和结果指标",
                    "为每条核心经历补充性能、效率、用户量或业务结果指标", "缓存命中率提升至 92%，数据库 QPS 降低 45%"));
        }
        if (depth < 75) {
            values.add(new DiagnosisSuggestion("P1", "技术深度", "技能数量较多，但缺少项目复杂度支撑",
                    "减少只罗列技能，用一个完整项目串联技术选型、架构和性能优化", "项目采用 Spring Boot、Redis、MySQL，并说明并发场景与优化结果"));
        }
        if (expression < 75) {
            values.add(new DiagnosisSuggestion("P1", "表达与ATS", "关键词密度或行动表达不足",
                    "使用“动作 + 技术 + 结果”重写自我评价和核心项目", "主导订单服务重构，使用 Redis 降低数据库压力"));
        }
        if (match != null && !missing.isEmpty()) {
            values.add(new DiagnosisSuggestion("P0", "岗位关键词", "目标岗位缺少结构化技能关键词",
                    "只补充真实掌握的技能，并在项目中体现使用场景", "建议优先学习并验证：" + String.join("、", missing.stream().limit(4).toList())));
        }
        if (match != null && match.matchScore() < 70) {
            values.add(new DiagnosisSuggestion("P1", "岗位选择", "当前岗位只能作为尝试方向",
                    "优先投递岗位方向和城市更匹配的岗位，再针对该岗位定制简历", "保留主简历，为后端、运维或数据方向分别生成版本"));
        }
        values.add(new DiagnosisSuggestion("P2", "投递策略", "一份简历投递所有岗位会降低匹配效率",
                "按岗位方向调整技能排序、项目侧重点和关键词", "针对 Java 后端突出 Spring Boot、MySQL、Redis；针对运维突出 Linux、Docker"));
        return values.stream().limit(7).toList();
    }

    private int dimensionScore(List<DiagnosisDimension> dimensions, String name) {
        return dimensions.stream().filter(item -> item.name().equals(name)).findFirst().map(DiagnosisDimension::score).orElse(0);
    }

    private String comment(int score, String subject) {
        if (score >= 85) return subject + "表现优秀";
        if (score >= 70) return subject + "基本合格，仍有优化空间";
        return subject + "需要重点补充";
    }
    private AiDiagnosis enrichWithAgent(ResumeView resume, String targetRole, List<DiagnosisDimension> dimensions,
                                        JobRecommendation match, String localSummary,
                                        List<DiagnosisSuggestion> localSuggestions, List<String> localStrengths) {
        String projectText = safe(resume.projects()).stream().limit(3)
                .map(project -> project.name() + " | 技术：" + safeText(project.techStack(), "未填写")
                        + " | 描述：" + safeText(project.description(), "未填写")
                        + " | 成果：" + safeText(project.highlight(), "未量化"))
                .collect(Collectors.joining("\n"));
        String qualityText = dimensions.stream().map(item -> item.name() + ":" + item.score() + "/100").collect(Collectors.joining("，"));
        String matchText = match == null ? "未选择具体岗位" : match.scoreBreakdown().stream()
                .filter(item -> item.weight() > 0)
                .map(item -> item.label() + ":" + item.contribution() + "（权重" + Math.round(item.weight()) + "%）")
                .collect(Collectors.joining("，"));
        int overall = match == null ? weightedScore(dimensions) : (int) Math.round(match.matchScore() * 0.6 + weightedScore(dimensions) * 0.4);
        String prompt = """
                你是计算机学生职业发展 Agent，负责在规则评分之上给出有针对性的深度诊断。
                候选人：%s
                目标岗位：%s
                技能：%s
                代表项目：
                %s

                规则评分：简历质量=%d，岗位匹配=%s，综合=%d
                岗位匹配拆解：%s
                简历质量维度：%s
                当前总结：%s

                要求：
                1. 不编造候选人的项目、技能或数据，只能基于给定事实提出建议。
                2. 必须解释失分来自哪些维度，不要把所有问题都归因于技能关键词。
                3. 给出 2-3 条按优先级排序的建议，问题和动作要具体。
                4. 示例只能是改写模板，不能伪装成候选人的真实经历。
                5. 返回 JSON。

                返回格式：
                {"summary":"120字以内总结","strengths":["优势1","优势2"],"suggestions":[{"priority":"P0或P1或P2","category":"类别","issue":"问题","action":"行动","example":"改写示例"}]}
                """.formatted(resume.ownerName(), targetRole, String.join("、", safe(resume.skills())),
                projectText.isBlank() ? "暂无项目" : projectText, weightedScore(dimensions),
                match == null ? "未计算" : match.matchScore() + "/100", overall, matchText, qualityText, localSummary);
        return agentModelClient.generateJson(prompt, AiDiagnosis.class);
    }

    private boolean isCurrent(DiagnosisReport report) {
        if (report == null || report.resumeUpdatedAt() == null) return false;
        ResumeView resume = resumeService.get(report.resumeId());
        return resume.updatedAt() == null || !resume.updatedAt().isAfter(report.resumeUpdatedAt());
    }
    private String level(int score) {
        if (score >= 85) return "竞争力强";
        if (score >= 72) return "基础良好";
        if (score >= 60) return "仍需打磨";
        return "需要重点优化";
    }

    public record AiDiagnosis(String summary, List<String> strengths, List<AiSuggestion> suggestions) {}
    public record AiSuggestion(String priority, String category, String issue, String action, String example) {}

    private String normalizePriority(String value) {
        String priority = value == null ? "P1" : value.trim().toUpperCase(Locale.ROOT);
        return priority.equals("P0") || priority.equals("P1") || priority.equals("P2") ? priority : "P1";
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : clip(value, 240);
    }

    private String clip(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private String n(String value) { return value == null ? "" : value; }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}