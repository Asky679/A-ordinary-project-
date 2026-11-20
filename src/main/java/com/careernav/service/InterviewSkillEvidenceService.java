package com.careernav.service;

import com.careernav.domain.ApiModels.InterviewMessage;
import com.careernav.domain.ApiModels.InterviewProjectEvidence;
import com.careernav.domain.ApiModels.JobInterviewBlueprint;
import com.careernav.domain.ApiModels.InterviewSkillEvidence;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 将面试记录转换为技能熟练度证据，并在简历诊断中复用。
 *
 * <p>面试官评分是证据，不是官方认证。模型不可用时只形成低置信度的自动汇总，
 * 不会把关键词提及直接当成已熟练掌握。</p>
 */
@Service
public class InterviewSkillEvidenceService {
    private final InterviewSessionRepository repository;
    private final JsonCodec jsonCodec;
    private final AgentModelClient model;

    public InterviewSkillEvidenceService(InterviewSessionRepository repository, JsonCodec jsonCodec,
                                         AgentModelClient model) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.model = model;
    }

    public List<InterviewSkillEvidence> assess(ResumeView resume, JobView job,
                                               List<InterviewMessage> messages, int overallScore,
                                               InterviewProjectEvidence projectEvidence) {
        return assess(resume, job, messages, overallScore, projectEvidence, null);
    }

    public List<InterviewSkillEvidence> assess(ResumeView resume, JobView job,
                                               List<InterviewMessage> messages, int overallScore,
                                               InterviewProjectEvidence projectEvidence,
                                               JobInterviewBlueprint jobBlueprint) {
        List<InterviewMessage> safeMessages = messages == null ? List.of() : messages;
        String transcript = transcript(safeMessages);
        Set<String> skills = targetSkills(resume, job, transcript, jobBlueprint);
        if (skills.isEmpty()) return List.of();
        List<InterviewSkillEvidence> assessed = model == null || !model.available()
                ? List.of() : assessWithModel(skills, safe(resume == null ? null : resume.skills()),
                        transcript, safeMessages, projectEvidence, jobBlueprint);
        if (assessed.isEmpty()) assessed = fallback(skills, transcript, safeMessages, overallScore);
        return assessed;
    }

    @Transactional(readOnly = true)
    public List<InterviewSkillEvidence> latestForResume(Long resumeId) {
        if (resumeId == null) return List.of();
        return mergeLatest(repository.findByResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId, "COMPLETED"));
    }

    @Transactional(readOnly = true)
    public List<InterviewSkillEvidence> latestForResumeAndJob(Long resumeId, Long jobId) {
        if (resumeId == null) return List.of();
        List<InterviewSessionEntity> sessions = repository.findByResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId, "COMPLETED");
        if (jobId != null) sessions = sessions.stream().filter(item -> jobId.equals(item.getJobId())).toList();
        return mergeLatest(sessions);
    }

    private List<InterviewSkillEvidence> mergeLatest(List<InterviewSessionEntity> sessions) {
        Map<String, InterviewSkillEvidence> latest = new LinkedHashMap<>();
        for (InterviewSessionEntity session : sessions) {
            List<InterviewSkillEvidence> rows = jsonCodec.readList(session.getSkillEvidenceJson(), InterviewSkillEvidence.class);
            String sessionTranscript = transcript(jsonCodec.readList(
                    session.getMessagesJson(), InterviewMessage.class));
            for (InterviewSkillEvidence row : rows) {
                if (row == null || row.skill() == null || row.skill().isBlank()) continue;
                // Old sessions may contain rows for resume skills that were never asked in this interview.
                if (!row.verified() && !skillMentioned(row.skill(), sessionTranscript)) continue;
                String key = normalize(row.skill());
                InterviewSkillEvidence old = latest.get(key);
                if (old == null || (row.updatedAt() != null && (old.updatedAt() == null || row.updatedAt().isAfter(old.updatedAt())))) {
                    latest.put(key, row);
                }
            }
        }
        return List.copyOf(latest.values());
    }

    private List<InterviewSkillEvidence> assessWithModel(Set<String> skills, List<String> resumeSkills,
                                                          String transcript, List<InterviewMessage> messages,
                                                          InterviewProjectEvidence projectEvidence,
                                                          JobInterviewBlueprint jobBlueprint) {
        String prompt = """
                你是计算机岗位的技能面试验证器。请只评估下面岗位要求中的技能。
                技能列表：%s
                候选人简历技能：%s
                脱敏项目证据卡：%s
                岗位面试蓝图：%s
                面试记录：
                <<<%s>>>

                规则：
                1. 必须结合候选人的实际回答判断，不能因为简历自述就判定熟练掌握。
                2. 职责边界、业务链路、技术实现、故障边界、方案取舍和结果验证都需要有回答证据。
                3. 技能证据必须能和项目证据卡中的角色、业务链路、实现、难点和结果相互印证。
                4. 技术概念错误、只有关键词或只有简历复述不能算已掌握。
                5. 每个技能给 0-100 分、0-1 置信度和最多 3 条可复核的回答证据。
                6. 未在面试中形成有效证据的技能，分数不超过 40，并明确写“面试未形成明确证据”。
                7. 不得编造候选人没有说过的经历或数字。
                8. 只返回 JSON：{"skills":[{"skill":"Java","score":72,"confidence":0.7,"evidence":["..."]}]}
                """.formatted(String.join("、", skills), String.join("、", resumeSkills),
                projectEvidenceText(projectEvidence), blueprintText(jobBlueprint), clip(transcript, 6500));
        ModelBatch batch = model.generateJson(prompt, ModelBatch.class);
        if (batch == null || batch.skills() == null) return List.of();
        Map<String, String> actual = skills.stream().collect(Collectors.toMap(this::normalize, value -> value, (a, b) -> a, LinkedHashMap::new));
        List<InterviewSkillEvidence> result = new ArrayList<>();
        for (ModelSkill row : batch.skills()) {
            if (row == null || row.skill() == null) continue;
            String actualSkill = actual.get(normalize(row.skill()));
            if (actualSkill == null) continue;
            int score = clamp((int) Math.round(row.score() == null ? 0 : row.score()), 0, 100);
            double confidence = clamp(row.confidence() == null ? 0.35 : row.confidence(), 0.0, 1.0);
            List<String> evidence = (row.evidence() == null ? List.<String>of() : row.evidence()).stream()
                    .filter(value -> value != null && !value.isBlank()).map(value -> clip(value, 180)).limit(3).toList();
            if (evidence.isEmpty()) evidence = List.of("面试回答未形成明确证据");
            boolean verified = score >= 60 && confidence >= 0.55 && !evidence.getFirst().contains("未形成明确证据");
            result.add(new InterviewSkillEvidence(actualSkill, score, level(score), confidence, verified,
                    "INTERVIEW", evidence, LocalDateTime.now()));
        }
        return result;
    }

    private List<InterviewSkillEvidence> fallback(Set<String> skills, String transcript,
                                                   List<InterviewMessage> messages, int overallScore) {
        String text = normalize(transcript);
        int fixedScore = clamp(overallScore, 0, 100);
        int answerCount = (int) messages.stream().filter(item -> "user".equals(item.role())).count();
        List<InterviewSkillEvidence> result = new ArrayList<>();
        for (String skill : skills) {
            if (!skillMentioned(skill, transcript)) continue;
            int score = Math.min(55, Math.max(35, fixedScore));
            double confidence = answerCount >= 3 ? 0.5 : 0.35;
            List<String> evidence = List.of("面试回答中提及该技能；当前为规则汇总，建议人工复核");
            result.add(new InterviewSkillEvidence(skill, score, level(score), confidence, false,
                    "INTERVIEW_FALLBACK", evidence, LocalDateTime.now()));
        }
        return result;
    }

    private String blueprintText(JobInterviewBlueprint blueprint) {
        if (blueprint == null) return "未提供";
        return String.join("；",
                "方向=" + safeText(blueprint.roleDirection()),
                "级别=" + safeText(blueprint.seniority()),
                "必备技能=" + String.join("、", blueprint.mustHaveSkills() == null ? List.of() : blueprint.mustHaveSkills()),
                "核心职责=" + String.join("、", blueprint.coreResponsibilities() == null ? List.of() : blueprint.coreResponsibilities()),
                "面试场景=" + String.join("、", blueprint.interviewScenarios() == null ? List.of() : blueprint.interviewScenarios()));
    }

    private String projectEvidenceText(InterviewProjectEvidence evidence) {
        if (evidence == null) return "未提供";
        return String.join("；", List.of(
                "项目=" + safeText(evidence.projectName()),
                "角色=" + safeText(evidence.role()),
                "团队=" + safeText(evidence.teamSize()),
                "业务链路=" + safeText(evidence.businessFlow()),
                "技术栈=" + safeText(evidence.techStack()),
                "关键实现=" + safeText(evidence.implementation()),
                "难点=" + safeText(evidence.challenge()),
                "结果证据=" + safeText(evidence.resultEvidence()),
                "信息范围=" + safeText(evidence.confidentiality())));
    }

    private String safeText(String value) { return value == null || value.isBlank() ? "未提供" : value.trim(); }

    private Set<String> targetSkills(ResumeView resume, JobView job, String transcript,
                                     JobInterviewBlueprint blueprint) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (blueprint != null) {
            if (blueprint.mustHaveSkills() != null) blueprint.mustHaveSkills().forEach(candidates::add);
            if (blueprint.niceToHaveSkills() != null) blueprint.niceToHaveSkills().forEach(candidates::add);
        }
        if (job != null && job.skills() != null) {
            job.skills().stream().filter(value -> value != null && !value.isBlank()).limit(12).forEach(candidates::add);
        }
        if (resume != null && resume.skills() != null) {
            resume.skills().stream().filter(value -> value != null && !value.isBlank()).limit(20).forEach(candidates::add);
        }
        // Only skills that actually appeared in this interview are eligible for interview evidence.
        return candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(skill -> skillMentioned(skill, transcript))
                .limit(10)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean skillMentioned(String skill, String text) {
        if (skill == null || skill.isBlank() || text == null || text.isBlank()) return false;
        String cleanSkill = skill.trim();
        if (cleanSkill.matches(".*[\u4e00-\u9fff].*")) {
            return normalize(text).contains(normalize(cleanSkill));
        }
        return Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(cleanSkill)
                        + "(?![A-Za-z0-9])")
                .matcher(text).find();
    }

    private String transcript(List<InterviewMessage> messages) {
        return messages.stream()
                .filter(item -> item != null && item.content() != null && !item.content().isBlank())
                .map(item -> ("user".equals(item.role()) ? "候选人：" : "面试官：") + item.content())
                .collect(Collectors.joining("\n"));
    }

    private String level(int score) {
        if (score >= 80) return "高级";
        if (score >= 60) return "中级";
        if (score >= 45) return "初级";
        return "待验证";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s._-]", "");
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private String clip(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max); }
    private List<String> safe(List<String> values) { return values == null ? List.of() : values; }

    public record ModelSkill(String skill, Integer score, Double confidence, List<String> evidence) {}
    public record ModelBatch(List<ModelSkill> skills) {}
}