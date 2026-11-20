package com.careernav.service;

import com.careernav.domain.ApiModels.JobInterviewBlueprint;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.ApiModels.ResumeView;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 将岗位数据库中的 JD 转成可执行、可追溯的面试蓝图。 */
@Service
public class JobInterviewBlueprintService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ITEMS = 8;
    private static final Pattern SENTENCE = Pattern.compile("[。；;\\n]+");
    private static final List<String> SKILL_VOCABULARY = List.of(
            "Java", "Python", "Spring Boot", "Spring", "MyBatis", "MySQL", "PostgreSQL", "Redis",
            "Kafka", "RabbitMQ", "Docker", "Kubernetes", "Linux", "SQL", "RESTful API", "微服务",
            "React", "Vue", "JavaScript", "TypeScript", "HTML5", "CSS3", "GraphQL", "AWS",
            "自动化测试", "功能测试", "接口测试", "Selenium", "JUnit", "性能优化", "高并发"
    );
    private final AgentModelClient model;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public JobInterviewBlueprintService(AgentModelClient model) {
        this.model = model;
    }

    public JobInterviewBlueprint build(JobView job, ResumeView resume) {
        if (job == null) throw new IllegalArgumentException("job is required");
        String key = cacheKey(job);
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.blueprint();

        JobInterviewBlueprint fallback = ruleBlueprint(job);
        JobInterviewBlueprint blueprint = mergeWithModel(job, resume, fallback);
        cache.put(key, new CacheEntry(blueprint, Instant.now().plus(TTL)));
        return blueprint;
    }

    private JobInterviewBlueprint mergeWithModel(JobView job, ResumeView resume, JobInterviewBlueprint fallback) {
        if (model == null || !model.available()) return fallback;
        String prompt = """
                你是招聘面试规划器。请只依据下面的岗位 JD、岗位技能、职责、任职要求、学历和经验，
                生成面试蓝图。不得添加 JD 中没有的必备技能，不得预设候选人经历。
                岗位名称：%s
                公司：%s
                岗位技能：%s
                经验要求：%s
                学历要求：%s
                岗位职责/描述：%s
                候选人简历技能（仅用于理解差距，不得写成岗位要求）：%s

                返回 JSON：
                {"roleDirection":"岗位方向","seniority":"实习/初级/中级/高级/未知",
                 "domain":"行业或业务领域","mustHaveSkills":["JD明确要求"],
                 "niceToHaveSkills":["JD优先或加分项"],
                 "coreResponsibilities":["职责1"],
                 "interviewScenarios":["面试中应覆盖的场景"],
                 "hardConstraints":["学历/经验/城市等硬条件"],
                 "companyStyle":"从公司类型和JD推断的面试风格",
                 "coveragePlan":["职责边界","业务链路","技术实现","故障与边界","方案取舍","结果验证"]}
                """.formatted(job.title(), job.company(), join(job.skills()), safe(job.experience()),
                safe(job.education()), clip(jobText(job), 9000),
                resume == null ? "未提供" : join(resume.skills()));
        ModelDraft draft = model.generateJson(prompt, ModelDraft.class);
        if (draft == null) return fallback;
        List<String> mustHave = unique(draft.mustHaveSkills());
        List<String> responsibilities = usefulResponsibilities(unique(draft.coreResponsibilities()), fallback.coreResponsibilities());
        List<String> scenarios = unique(draft.interviewScenarios());
        return new JobInterviewBlueprint(
                job.id(), job.title(), job.company(),
                first(draft.roleDirection(), fallback.roleDirection()),
                first(draft.seniority(), fallback.seniority()),
                first(draft.domain(), fallback.domain()),
                mustHave.isEmpty() ? fallback.mustHaveSkills() : limit(mustHave),
                unique(draft.niceToHaveSkills()).isEmpty() ? fallback.niceToHaveSkills() : limit(unique(draft.niceToHaveSkills())),
                responsibilities.isEmpty() ? fallback.coreResponsibilities() : limit(responsibilities),
                scenarios.isEmpty() ? fallback.interviewScenarios() : limit(scenarios),
                unique(draft.hardConstraints()).isEmpty() ? fallback.hardConstraints() : limit(unique(draft.hardConstraints())),
                first(draft.companyStyle(), fallback.companyStyle()),
                unique(draft.coveragePlan()).isEmpty() ? fallback.coveragePlan() : limit(unique(draft.coveragePlan())),
                "由岗位库中的 JD、技能、职责、经验、学历和行业数据生成；AI 不可用时使用规则提取"
        );
    }

    private JobInterviewBlueprint ruleBlueprint(JobView job) {
        List<String> skills = unique(job.skills());
        if (skills.size() < 2) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(skills);
            merged.addAll(extractSkills(job.title() + " " + jobText(job)));
            skills = limit(List.copyOf(merged));
        }
        List<String> responsibilities = usefulResponsibilities(sentences(jobText(job), 12),
                List.of("围绕岗位核心技能完成功能开发和接口交付", "参与需求沟通、联调和问题排查"));
        List<String> scenarios = scenarios(job, skills);
        List<String> constraints = new ArrayList<>();
        if (job.education() != null && !job.education().isBlank()) constraints.add("学历：" + job.education());
        if (job.experience() != null && !job.experience().isBlank()) constraints.add("经验：" + job.experience());
        if (job.city() != null && !job.city().isBlank()) constraints.add("工作城市：" + job.city());
        String domain = first(job.category(), first(job.industry(), "计算机/软件"));
        String seniority = seniority(job.experience());
        String style = first(job.industry(), "按岗位职责进行技术面试")
                + (job.companySize() == null || job.companySize().isBlank() ? "" : "；公司规模：" + job.companySize());
        return new JobInterviewBlueprint(job.id(), job.title(), job.company(), titleDirection(job.title()),
                seniority, domain, limit(skills), List.<String>of(), limit(responsibilities), limit(scenarios),
                limit(unique(constraints)), style,
                List.of("职责边界", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证"),
                "由岗位库中的 JD、技能、职责、经验和行业数据规则提取");
    }

    private List<String> usefulResponsibilities(List<String> values, List<String> fallback) {
        List<String> filtered = (values == null ? List.<String>of() : values).stream()
                .map(this::safe)
                .filter(value -> !value.isBlank())
                .filter(value -> !isGenericDuty(value))
                .limit(MAX_ITEMS)
                .toList();
        return filtered.isEmpty() ? limit(fallback) : filtered;
    }

    private boolean isGenericDuty(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        return List.of("完成上级", "领导交办", "领导安排", "服从安排", "其他工作",
                        "临时工作", "临时任务", "公司安排", "完成领导", "boss直聘")
                .stream().anyMatch(text::contains);
    }
    private List<String> extractSkills(String text) {
        String source = safe(text);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String skill : SKILL_VOCABULARY) {
            if (skillMentioned(skill, source)) result.add(skill);
        }
        return List.copyOf(result);
    }

    private boolean skillMentioned(String skill, String text) {
        if (skill == null || skill.isBlank() || text == null || text.isBlank()) return false;
        String clean = skill.trim();
        if (clean.matches(".*[\\u4e00-\\u9fff].*")) return text.contains(clean);
        return Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(clean) + "(?![A-Za-z0-9])")
                .matcher(text).find();
    }

    private List<String> scenarios(JobView job, List<String> skills) {
        String text = (safe(job.title()) + " " + join(skills) + " " + jobText(job)).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (contains(text, "测试", "test", "qa")) result.add("测试用例设计、异常验证与缺陷定位");
        if (contains(text, "订单", "交易", "支付")) result.add("订单链路、幂等、库存或支付一致性");
        if (contains(text, "redis", "缓存")) result.add("缓存一致性、失效、穿透与降级");
        if (contains(text, "并发", "高并发", "线程")) result.add("并发冲突、锁竞争与失败恢复");
        if (contains(text, "spring", "微服务", "接口")) result.add("接口分层、事务边界、异常处理与调用链");
        if (contains(text, "数据", "sql", "数据库")) result.add("数据建模、索引和查询结果验证");
        if (result.isEmpty()) result.addAll(List.of("项目职责边界", "核心业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证"));
        return limit(result.stream().toList());
    }

    private String seniority(String experience) {
        String value = safe(experience).toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.contains("不限") || value.contains("0-0") || value.contains("实习")) return "实习/初级/未知";
        if (value.contains("1-3") || value.contains("1年")) return "初级";
        if (value.contains("3-5") || value.contains("3年")) return "中级";
        return "按JD经验要求判断";
    }

    private String titleDirection(String title) {
        String value = safe(title);
        if (value.isBlank()) return "未明确岗位方向";
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private String jobText(JobView job) {
        return String.join("\n", List.of(safe(job.description()), safe(job.jobDescription()), safe(job.jobRequirements())));
    }

    private List<String> sentences(String text, int max) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : SENTENCE.split(safe(text).replaceAll("<[^>]+>", " "))) {
            String line = raw.replaceAll("\\s+", " ").trim();
            if (line.length() >= 4 && line.length() <= 180) result.add(line);
            if (result.size() >= max) break;
        }
        return List.copyOf(result);
    }

    private List<String> unique(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String clean = value == null ? "" : value.trim();
            if (!clean.isBlank()) result.add(clean.length() > 160 ? clean.substring(0, 160) : clean);
            if (result.size() >= MAX_ITEMS) break;
        }
        return List.copyOf(result);
    }

    private List<String> limit(List<String> values) {
        return values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).limit(MAX_ITEMS).toList();
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join("、", values.stream().filter(Objects::nonNull).toList());
    }

    private boolean contains(String text, String... terms) {
        return List.of(terms).stream().anyMatch(text::contains);
    }

    private String first(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    private String cacheKey(JobView job) {
        return job.id() + ":" + Objects.hash(job.title(), job.company(), job.skills(), job.description(),
                job.jobDescription(), job.jobRequirements(), job.experience(), job.education());
    }

    private record CacheEntry(JobInterviewBlueprint blueprint, Instant expiresAt) {}
    public record ModelDraft(String roleDirection, String seniority, String domain,
                             List<String> mustHaveSkills, List<String> niceToHaveSkills,
                             List<String> coreResponsibilities, List<String> interviewScenarios,
                             List<String> hardConstraints, String companyStyle,
                             List<String> coveragePlan) {}
}
