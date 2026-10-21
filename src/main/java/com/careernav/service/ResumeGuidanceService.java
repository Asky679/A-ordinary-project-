package com.careernav.service;

import com.careernav.domain.ApiModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 引导式简历编写：方向画像 + 技能证据 + 质量完成度 + 项目引导。
 *
 * <p>方向画像完全来自库里的真实岗位数据（按 category 聚合 skills_json），
 * 不依赖任何外部服务，因此可以离线运行、结果可复现。
 */
@Service
public class ResumeGuidanceService {
    private static final int TOP_SKILLS = 12;
    private static final int COVERAGE_TARGET = 8;

    private final JdbcTemplate jdbc;
    private final ResumeService resumes;
    private final JsonCodec jsonCodec;
    private final AgentModelClient model;

    public ResumeGuidanceService(JdbcTemplate jdbc, ResumeService resumes,
                                 JsonCodec jsonCodec, AgentModelClient model) {
        this.jdbc = jdbc;
        this.resumes = resumes;
        this.jsonCodec = jsonCodec;
        this.model = model;
    }

    @Transactional(readOnly = true)
    public List<String> directions() {
        return jdbc.queryForList("""
                SELECT DISTINCT category FROM jobs
                WHERE status = 'ACTIVE' AND category IS NOT NULL AND category <> ''
                ORDER BY category
                """, String.class);
    }

    @Transactional(readOnly = true)
    public DirectionProfile profile(String role) {
        String target = role == null ? "" : role.trim();
        List<String> rows;
        if (target.isBlank()) {
            rows = jdbc.queryForList("SELECT skills_json FROM jobs WHERE status='ACTIVE'", String.class);
        } else {
            // 目标岗位是自由文本（如 "python"），分类是规范值（"Python"），
            // 因此先忽略大小写与空格匹配，再退化为包含匹配。
            rows = jdbc.queryForList("""
                    SELECT skills_json FROM jobs
                    WHERE status='ACTIVE'
                      AND LOWER(REPLACE(category,' ','')) = LOWER(REPLACE(?,' ',''))
                    """, String.class, target);
            if (rows.isEmpty()) {
                rows = jdbc.queryForList("""
                        SELECT skills_json FROM jobs
                        WHERE status='ACTIVE' AND LOWER(category) LIKE CONCAT('%', LOWER(?), '%')
                        """, String.class, target);
            }
        }
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String json : rows) {
            for (String skill : new LinkedHashSet<>(jsonCodec.readList(json, String.class))) {
                if (skill != null && !skill.isBlank()) counter.merge(skill.trim(), 1, Integer::sum);
            }
        }
        int total = rows.size();
        List<SkillStat> top = counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_SKILLS)
                .map(e -> new SkillStat(e.getKey(), e.getValue(),
                        total == 0 ? 0 : Math.round(100f * e.getValue() / total)))
                .toList();
        return new DirectionProfile(target.isBlank() ? "全部方向" : target, total, top);
    }

    @Transactional(readOnly = true)
    public GuidanceReport analyze(Long resumeId, String role) {
        ResumeView resume = resumes.get(resumeId);
        DirectionProfile profile = profile(role);
        List<String> skills = safe(resume.skills());
        Set<String> normalized = new LinkedHashSet<>();
        for (String skill : skills) normalized.add(norm(skill));

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (SkillStat stat : profile.topSkills()) {
            if (normalized.contains(norm(stat.skill()))) matched.add(stat.skill());
            else missing.add(stat.skill());
        }

        String evidenceText = evidenceText(resume);
        List<SkillEvidence> evidences = new ArrayList<>();
        for (String skill : skills) {
            boolean ok = evidenceText.contains(norm(skill));
            evidences.add(new SkillEvidence(skill, ok,
                    ok ? "项目或经历中已出现" : "技能栏有，但项目和经历里没有出现"));
        }

        QualityScore quality = quality(resume, profile, matched, evidences);
        return new GuidanceReport(resumeId, role, profile, matched, missing, evidences, quality,
                questions(role, profile));
    }

    /** 根据五问回答拼装项目描述；可选 AI 润色，但严禁增加用户未提供的事实。 */
    public ProjectDraft draft(ProjectDraftRequest request) {
        List<String> facts = new ArrayList<>();
        addFact(facts, "背景", request.background());
        addFact(facts, "职责", request.responsibility());
        addFact(facts, "技术栈", request.techStack());
        addFact(facts, "难点", request.challenge());
        addFact(facts, "结果", request.result());

        String template = buildTemplate(request);
        if (request.useAi() && model.available() && !facts.isEmpty()) {
            String prompt = """
                    你是简历写作助手。请把下面这些事实改写成一段自然、专业的中文项目描述，控制在 80~140 字。
                    硬性要求：
                    1. 只能使用给定事实，绝对不要添加任何新的公司、技术、数字或结果；
                    2. 不要编造用户没有提供的经历；
                    3. 直接输出改写后的段落，不要解释，不要 Markdown。
                    事实：
                    """ + String.join("\n", facts);
            String rewritten = model.generate(prompt);
            if (rewritten != null && !rewritten.isBlank()) {
                return new ProjectDraft(rewritten.trim(), clean(request.result()), "AI 改写", facts);
            }
        }
        return new ProjectDraft(template, clean(request.result()), "模板拼装", facts);
    }

    // ---------------------------------------------------------------- 内部
    private QualityScore quality(ResumeView r, DirectionProfile profile,
                                 List<String> matched, List<SkillEvidence> evidences) {
        int completeness = 0;
        if (nonBlank(r.ownerName())) completeness += 3;
        if (nonBlank(r.targetRole())) completeness += 3;
        if (nonBlank(r.city())) completeness += 2;
        if (nonBlank(r.email()) || nonBlank(r.phone())) completeness += 3;
        if (nonBlank(r.education())) completeness += 3;
        if (nonBlank(r.school())) completeness += 2;
        if (safe(r.skills()).size() >= 3) completeness += 4;
        if (!safe(r.projects()).isEmpty()) completeness += 5;
        if (nonBlank(r.selfEvaluation())) completeness += 2;
        if (!safe(r.experiences()).isEmpty()) completeness += 3;
        completeness = Math.min(30, completeness);

        int target = Math.max(1, Math.min(COVERAGE_TARGET, profile.topSkills().size()));
        int coverage = (int) Math.round(30.0 * Math.min(matched.size(), target) / target);

        long evidenced = evidences.stream().filter(SkillEvidence::evidenced).count();
        int evidence = evidences.isEmpty() ? 0
                : (int) Math.round(25.0 * evidenced / evidences.size());

        List<ProjectItem> projects = safe(r.projects());
        long quantified = projects.stream()
                .filter(p -> hasDigit(p.highlight()) || hasDigit(p.description())).count();
        int quantifiedScore = projects.isEmpty() ? 0
                : (int) Math.round(15.0 * quantified / projects.size());

        List<String> suggestions = new ArrayList<>();
        if (coverage < 30) suggestions.add("补充目标方向的高频技能，优先补 " + String.join("、", firstMissing(matched, profile)));
        if (evidence < 25) suggestions.add("技能栏里有 " + (evidences.size() - evidenced) + " 项在项目中找不到证据，建议补项目或从技能栏移除");
        if (quantifiedScore < 15) suggestions.add("至少给一个项目补上可量化的结果（基线 → 优化后）");
        if (completeness < 30) suggestions.add("补齐基本信息、教育背景与自我评价");
        if (suggestions.isEmpty()) suggestions.add("各维度都较完整，可以进入岗位匹配与诊断");

        int total = completeness + coverage + evidence + quantifiedScore;
        return new QualityScore(total, completeness, coverage, evidence, quantifiedScore, suggestions);
    }

    private List<String> firstMissing(List<String> matched, DirectionProfile profile) {
        Set<String> m = new HashSet<>(matched);
        return profile.topSkills().stream().map(SkillStat::skill)
                .filter(s -> !m.contains(s)).limit(3).toList();
    }

    private List<GuidanceQuestion> questions(String role, DirectionProfile profile) {
        String dir = role == null || role.isBlank() ? "目标方向" : role;
        String top = profile.topSkills().stream().limit(3).map(SkillStat::skill)
                .reduce((a, b) -> a + " / " + b).orElse("常用技术");
        return List.of(
                new GuidanceQuestion("background", "这个项目解决什么问题？",
                        "一句话说明场景和痛点，不要写“提高能力”这类空话。",
                        "校园闲置物品交易分散在多个群里，信息更新不及时"),
                new GuidanceQuestion("responsibility", "你具体负责哪部分？",
                        "区分“我参与”和“我负责”，写清你主导的模块。",
                        "负责商品发布、列表查询、下单和订单状态流转四个模块"),
                new GuidanceQuestion("techStack", "用了什么技术栈？",
                        "写 " + dir + " 方向真正用到的技术，例如 " + top + "。",
                        "Spring Boot + MySQL + Redis"),
                new GuidanceQuestion("challenge", "最大的技术难点是什么？怎么解决的？",
                        "面试官最关心这里：说清问题现象、定位过程和最终方案。",
                        "热门商品查询慢，用 Redis 缓存热点数据并设置合理过期时间"),
                new GuidanceQuestion("result", "最终有什么可量化的结果？",
                        "给出基线 → 优化后的对比，没有数字就写真实的影响范围。",
                        "接口 P95 从 480ms 降到 90ms，可支撑 3000+ 用户同时在线")
        );
    }

    private String buildTemplate(ProjectDraftRequest r) {
        List<String> parts = new ArrayList<>();
        if (nonBlank(r.background())) parts.add(clean(r.background()) + "。");
        if (nonBlank(r.responsibility())) parts.add("我负责" + clean(r.responsibility()) + "。");
        if (nonBlank(r.techStack())) parts.add("技术栈为" + clean(r.techStack()) + "。");
        if (nonBlank(r.challenge())) parts.add("主要难点是" + clean(r.challenge()) + "。");
        if (nonBlank(r.result())) parts.add("最终" + clean(r.result()) + "。");
        return String.join("", parts);
    }

    private String evidenceText(ResumeView r) {
        StringBuilder sb = new StringBuilder();
        for (ProjectItem p : safe(r.projects())) {
            sb.append(nz(p.name())).append(' ').append(nz(p.role())).append(' ')
              .append(nz(p.techStack())).append(' ').append(nz(p.description())).append(' ')
              .append(nz(p.highlight())).append(' ');
        }
        for (ExperienceItem e : safe(r.experiences())) {
            sb.append(nz(e.company())).append(' ').append(nz(e.position())).append(' ')
              .append(nz(e.description())).append(' ').append(nz(e.achievement())).append(' ');
        }
        sb.append(nz(r.selfEvaluation()));
        return norm(sb.toString());
    }

    private static final Map<String, String> ALIASES = Map.of(
            "k8s", "kubernetes", "js", "javascript", "pg", "postgresql",
            "springboot", "spring", "nodejs", "node", "golang", "go", "cpp", "c++");

    private String norm(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u4e00-\u9fff]", "");
        return ALIASES.getOrDefault(s, s);
    }

    private boolean hasDigit(String value) { return value != null && value.matches(".*\\d.*"); }

    private void addFact(List<String> facts, String label, String value) {
        if (nonBlank(value)) facts.add(label + "：" + clean(value));
    }

    private <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }

    private boolean nonBlank(String v) { return v != null && !v.isBlank(); }

    private String clean(String v) { return v == null ? "" : v.trim(); }

    private String nz(String v) { return v == null ? "" : v; }
}
