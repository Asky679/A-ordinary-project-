package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.InterviewSessionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InterviewService {
    private static final int TOTAL_ROUNDS = 8;
    private final InterviewSessionRepository repository;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final JsonCodec jsonCodec;
    private final AgentModelClient agentModelClient;
    private final LangGraphInterviewClient langGraphClient;
    private final InterviewSkillEvidenceService skillEvidenceService;
    private final JobInterviewBlueprintService blueprintService;

    public InterviewService(InterviewSessionRepository repository, ResumeService resumeService,
                            JobService jobService, JsonCodec jsonCodec,
                            AgentModelClient agentModelClient, LangGraphInterviewClient langGraphClient,
                            InterviewSkillEvidenceService skillEvidenceService,
                            JobInterviewBlueprintService blueprintService) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
        this.agentModelClient = agentModelClient;
        this.langGraphClient = langGraphClient;
        this.skillEvidenceService = skillEvidenceService;
        this.blueprintService = blueprintService;
    }

    @Transactional
    public InterviewView start(InterviewStartRequest request) {
        ResumeView resume = resumeService.get(request.resumeId());
        JobView job = jobService.get(request.jobId());
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "标准" : request.difficulty();
        InterviewProjectEvidence projectEvidence = projectEvidence(request.projectEvidence(), resume);
        JobInterviewBlueprint jobBlueprint = blueprintService.build(job, resume);
        List<InterviewMessage> messages = new ArrayList<>();
        messages.add(new InterviewMessage("assistant", firstQuestion(resume, job, difficulty, projectEvidence),
                LocalDateTime.now(), null, null));

        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setResumeId(resume.id());
        entity.setJobId(job.id());
        entity.setJobTitle(job.title());
        entity.setDifficulty(difficulty);
        entity.setStatus("IN_PROGRESS");
        entity.setCurrentRound(1);
        entity.setTotalRounds(TOTAL_ROUNDS);
        entity.setOverallScore(0);
        entity.setMessagesJson(jsonCodec.write(messages));
        entity.setProjectEvidenceJson(jsonCodec.write(projectEvidence));
        entity.setJobBlueprintJson(jsonCodec.write(jobBlueprint));
        entity.setStartedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getStartedAt());
        entity = repository.save(entity);

        if (langGraphClient.available()) {
            LangGraphInterviewClient.AgentTurn turn = langGraphClient.start(entity.getId(), resume, job, difficulty, projectEvidence, jobBlueprint);
            if (turn != null && turn.question() != null && !turn.question().isBlank()) {
                List<InterviewMessage> agentMessages = new ArrayList<>();
                agentMessages.add(new InterviewMessage("assistant", turn.question(), LocalDateTime.now(), null, null));
                entity.setMessagesJson(jsonCodec.write(agentMessages));
                entity.setCurrentRound(Math.max(1, turn.round()));
                entity.setUpdatedAt(LocalDateTime.now());
                entity = repository.save(entity);
            }
        }
        return toView(entity);
    }

    @Transactional
    public InterviewView answer(Long id, String answer) {
        InterviewSessionEntity entity = getEntity(id);
        if ("COMPLETED".equals(entity.getStatus())) {
            throw new BusinessException("本场模拟面试已结束");
        }
        List<InterviewMessage> messages = new ArrayList<>(
                jsonCodec.readList(entity.getMessagesJson(), InterviewMessage.class));
        JobView job = jobService.get(entity.getJobId());
        ResumeView resume = resumeService.get(entity.getResumeId());
        InterviewProjectEvidence projectEvidence = readProjectEvidence(entity, resume);
        JobInterviewBlueprint jobBlueprint = readJobBlueprint(entity);

        if (langGraphClient.available()) {
            LangGraphInterviewClient.AgentTurn turn = langGraphClient.answer(id, answer, resume, job, projectEvidence, jobBlueprint);
            if (turn != null) {
                int score = turn.score() == null ? 0 : Math.max(0, Math.min(100, turn.score()));
                String feedback = turn.feedback() == null || turn.feedback().isBlank()
                        ? "已完成本轮回答评估。" : turn.feedback();
                messages.add(new InterviewMessage("user", answer.trim(), LocalDateTime.now(), score, feedback));
                messages.add(new InterviewMessage("assistant", feedback, LocalDateTime.now(), null, null));

                int answered = entity.getCurrentRound();
                int oldTotal = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
                entity.setOverallScore((int) Math.round(
                        (oldTotal * (answered - 1) + score) / (double) answered));

                if (answered >= entity.getTotalRounds()) {
                    entity.setStatus("COMPLETED");
                    entity.setCurrentRound(entity.getTotalRounds());
                    saveSkillEvidence(entity, resume, job, messages);
                    messages.add(new InterviewMessage("assistant",
                            "本场 LangGraph 面试已结束。Agent 已根据回答、岗位要求和会话记忆生成技能证据与总结。",
                            LocalDateTime.now(), null, null));
                } else {
                    entity.setCurrentRound(Math.max(answered + 1, turn.round()));
                    String next = turn.question();
                    if (next == null || next.isBlank()) {
                        next = nextQuestion(entity.getCurrentRound(), resume, job);
                    }
                    messages.add(new InterviewMessage("assistant", next, LocalDateTime.now(), null, null));
                }
                entity.setMessagesJson(jsonCodec.write(messages));
                entity.setUpdatedAt(LocalDateTime.now());
                return toView(repository.save(entity));
            }
        }

        Evaluation evaluation = evaluate(answer, job, resume,
                entity.getCurrentRound(), entity.getTotalRounds());
        messages.add(new InterviewMessage("user", answer.trim(), LocalDateTime.now(),
                evaluation.score(), evaluation.feedback()));
        messages.add(new InterviewMessage("assistant", evaluation.feedback(), LocalDateTime.now(), null, null));

        int answered = entity.getCurrentRound();
        int oldTotal = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
        entity.setOverallScore((int) Math.round(
                (oldTotal * (answered - 1) + evaluation.score()) / (double) answered));

        if (answered >= entity.getTotalRounds()) {
            entity.setStatus("COMPLETED");
            saveSkillEvidence(entity, resume, job, messages);
            messages.add(new InterviewMessage("assistant",
                    "本场模拟面试结束。AI 面试官已将回答中的技术点和表达问题整理为技能证据。下一步请在简历诊断中查看技能验证结果。",
                    LocalDateTime.now(), null, null));
        } else {
            entity.setCurrentRound(answered + 1);
            String next = evaluation.nextQuestion();
            if (next == null || next.isBlank()) {
                next = nextQuestion(entity.getCurrentRound(), resume, job);
            }
            messages.add(new InterviewMessage("assistant", next, LocalDateTime.now(), null, null));
        }
        entity.setMessagesJson(jsonCodec.write(messages));
        entity.setUpdatedAt(LocalDateTime.now());
        return toView(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<InterviewSkillEvidence> latestSkillEvidence(Long resumeId, Long jobId) {
        resumeService.get(resumeId);
        return skillEvidenceService.latestForResumeAndJob(resumeId, jobId);
    }

    public JobInterviewBlueprint blueprint(Long jobId) {
        return blueprintService.build(jobService.get(jobId), null);
    }

    @Transactional(readOnly = true)
    public InterviewView get(Long id) {
        return toView(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<InterviewView> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    private InterviewSessionEntity getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException("面试记录不存在：" + id));
    }

    private String firstQuestion(ResumeView resume, JobView job, String difficulty,
                                 InterviewProjectEvidence evidence) {
        String project = evidence == null || evidence.projectName() == null || evidence.projectName().isBlank()
                ? (resume.projects().isEmpty() ? "你的代表项目" : resume.projects().getFirst().name())
                : evidence.projectName();
        String role = evidence == null || evidence.role() == null || evidence.role().isBlank()
                ? "你本人负责的模块" : evidence.role();
        String fallback = String.format(
                "你好，欢迎参加“%s”岗位模拟面试，本轮强度为%s。第一轮先聚焦项目职责：请用两分钟介绍“%s”，重点说明项目背景、团队规模、你负责的模块，以及哪些工作是你独立完成的。如果需要描述企业项目，只讲脱敏后的模块和业务，不需要提供源码或公司敏感信息。",
                job.title(), difficulty, project);
        String aiQuestion = agentModelClient.generate("""
                你是计算机校园招聘的专业面试官。请根据以下信息生成第一轮项目职责问题。
                岗位：%s
                岗位技能：%s
                候选人目标：%s
                项目证据卡：%s
                面试强度：%s

                要求：
                1. 第一轮围绕项目背景、团队规模、个人职责和模块边界提问，不要先问八股。
                2. 企业项目可能涉密，明确允许候选人使用脱敏模块名称，不要要求源码或内部数据。
                3. 问题必须引用项目证据卡中的项目名称或角色，不能问泛泛的“介绍一下自己”。
                4. 只输出面试官要说的话，控制在 120 字以内。
                """.formatted(job.title(), String.join("、", job.skills()), resume.targetRole(),
                jsonCodec.write(evidence), difficulty));
        return aiQuestion == null ? fallback : clip(aiQuestion, 500);
    }

    private String nextQuestion(int round, ResumeView resume, JobView job) {
        List<String> missing = job.skills().stream()
                .filter(skill -> resume.skills().stream().noneMatch(owned -> owned.equalsIgnoreCase(skill)))
                .toList();
        String skill = missing.isEmpty()
                ? job.skills().stream().filter(item -> item != null && !item.isBlank()).findFirst().orElse("岗位核心技能")
                : missing.getFirst();
        String project = resume.projects().isEmpty() ? "你的代表项目" : resume.projects().getFirst().name();
        return switch (round) {
            case 2 -> "请选一条项目最核心的端到端业务链路，按用户请求、服务处理、数据变化和返回结果讲清楚，并说明你负责哪一段。";
            case 3 -> "接下来验证技术实现：请说明 " + skill + " 在“" + project + "”中的具体落点、关键数据结构，以及你亲手实现的部分。";
            case 4 -> "如果这条链路遇到异常输入、并发冲突、超时或依赖不可用，你会怎么定位、隔离和恢复？请说明判断依据。";
            case 5 -> "请说明你在项目中做过的一个技术取舍：当时有哪些替代方案，你为什么选当前方案，又牺牲了什么？";
            case 6 -> "请说明项目成果和验证方式：基线是什么、怎么测试、数据如何得到，以及你个人能对哪些结果负责。";
            case 7 -> "请补强一个刚才没有讲透的点：如果面试官继续追问你的个人贡献、边界情况或验证证据，你会怎么回答？";
            case 8 -> "最后一个问题：如果项目规模扩大10倍或增加一项新需求，你会如何调整当前设计？";
            default -> "请结合岗位要求，说明你还能提供哪项可验证的技术证据。";
        };
    }

    private Evaluation evaluate(String answer, JobView job, ResumeView resume,
                                int currentRound, int totalRounds) {
        String text = answer.trim();
        int score = 35;
        List<String> signals = new ArrayList<>();
        if (text.length() >= 80) { score += 6; signals.add("内容较完整"); }
        if (text.length() >= 180) { score += 5; signals.add("细节充分"); }
        List<String> structureWords = List.of("首先", "其次", "最后", "背景", "任务", "行动", "结果", "star");
        long structureHits = structureWords.stream().filter(text.toLowerCase(Locale.ROOT)::contains).count();
        if (structureHits >= 2) { score += 12; signals.add("表达有结构"); }
        long techHits = job.skills().stream()
                .filter(skill -> text.toLowerCase(Locale.ROOT).contains(skill.toLowerCase(Locale.ROOT))).count();
        score += (int) Math.min(15, techHits * 4);
        if (techHits > 0) signals.add("技术关键词命中");
        if (text.matches(".*\\d+.*")) { score += 7; signals.add("使用了数据证据"); }
        score = Math.max(0, Math.min(90, score));
        String feedback = signals.isEmpty()
                ? "回答方向正确，但信息密度偏低。建议使用 STAR 结构，并补充技术选型、个人动作和量化结果。"
                : "表现亮点：" + String.join("、", signals) + "。建议继续补充取舍理由和可验证结果，回答会更有说服力。";
        String fallbackNext = currentRound >= totalRounds ? null : nextQuestion(currentRound + 1, resume, job);

        String projects = resume.projects().stream().limit(2)
                .map(item -> item.name() + "：" + safe(item.description()) + "；成果：" + safe(item.highlight()))
                .collect(Collectors.joining("\n"));
        AgentEvaluation ai = agentModelClient.generateJson("""
                你是严格但友善的计算机岗位面试官，负责形成可追溯的技能证据并追问。
                岗位：%s
                岗位要求：%s
                候选人技能自述：%s
                候选项目自述：%s
                当前轮次：%d / %d
                本场验证阶段依次为：职责边界、业务链路、技术实现、故障与边界、方案取舍、结果验证、证据补强、迁移验证。
                候选人回答：
                <<<%s>>>

                评分必须判断技术正确性和证据质量，不能只因为回答长、关键词多就给高分。
                如果概念错误、只有定义没有应用、把团队成果说成个人成果、或无法说明验证方式，应明确扣分。
                返回 JSON：
                {"score":0到100的整数,"feedback":"指出正确点、错误或缺失证据，100字以内","nextQuestion":"下一轮紧扣回答继续深挖；已是最后一轮则返回空字符串"}
                不得编造候选人的经历，nextQuestion 必须基于回答或简历继续深挖。
                """.formatted(job.title(), String.join("、", job.skills()),
                String.join("、", resume.skills()), projects.isBlank() ? "暂无项目" : projects,
                currentRound, totalRounds, text), AgentEvaluation.class);

        if (ai != null && ai.score() != null) {
            int aiScore = Math.max(0, Math.min(100, ai.score()));
            score = (score + aiScore) / 2;
            if (ai.feedback() != null && !ai.feedback().isBlank()) {
                feedback = clip(ai.feedback(), 360);
            }
            if (ai.nextQuestion() != null && !ai.nextQuestion().isBlank()) {
                fallbackNext = clip(ai.nextQuestion(), 360);
            }
        }
        return new Evaluation(score, feedback, fallbackNext);
    }

    private void saveSkillEvidence(InterviewSessionEntity entity, ResumeView resume, JobView job,
                                    List<InterviewMessage> messages) {
        int overall = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
        List<InterviewSkillEvidence> evidence = skillEvidenceService.assess(resume, job, messages, overall,
                readProjectEvidence(entity, resume), readJobBlueprint(entity));
        entity.setSkillEvidenceJson(jsonCodec.write(evidence));
    }

    private InterviewView toView(InterviewSessionEntity entity) {
        return new InterviewView(entity.getId(), entity.getResumeId(), entity.getJobId(), entity.getJobTitle(),
                entity.getDifficulty(), entity.getStatus(),
                entity.getCurrentRound() == null ? 0 : entity.getCurrentRound(),
                entity.getTotalRounds() == null ? TOTAL_ROUNDS : entity.getTotalRounds(),
                entity.getOverallScore() == null ? 0 : entity.getOverallScore(),
                jsonCodec.readList(entity.getMessagesJson(), InterviewMessage.class),
                jsonCodec.readList(entity.getSkillEvidenceJson(), InterviewSkillEvidence.class),
                readProjectEvidence(entity, null),
                readJobBlueprint(entity),
                entity.getStartedAt(), entity.getUpdatedAt());
    }

    private InterviewProjectEvidence projectEvidence(InterviewProjectEvidence supplied, ResumeView resume) {
        ProjectItem project = resume.projects().isEmpty() ? null : resume.projects().getFirst();
        String fallbackName = project == null ? "待确认项目" : safe(project.name());
        String fallbackRole = project == null ? "" : safe(project.role());
        String fallbackTech = project == null ? "" : safe(project.techStack());
        String fallbackDescription = project == null ? "" : safe(project.description());
        String fallbackChallenge = project == null ? "" : safe(project.challenge());
        String fallbackResult = project == null ? "" : safe(project.highlight());
        return new InterviewProjectEvidence(
                preferred(supplied == null ? null : supplied.projectName(), fallbackName),
                preferred(supplied == null ? null : supplied.role(), fallbackRole),
                clip(supplied == null ? "" : safe(supplied.teamSize()), 80),
                preferred(supplied == null ? null : supplied.businessFlow(), fallbackDescription),
                preferred(supplied == null ? null : supplied.techStack(), fallbackTech),
                preferred(supplied == null ? null : supplied.implementation(), fallbackDescription),
                preferred(supplied == null ? null : supplied.challenge(), fallbackChallenge),
                preferred(supplied == null ? null : supplied.resultEvidence(), fallbackResult),
                preferred(supplied == null ? null : supplied.confidentiality(), "企业保密项目"),
                clip(supplied == null ? "" : safe(supplied.notes()), 1000),
                supplied == null || supplied.evidenceDetails() == null
                        ? Map.of()
                        : new LinkedHashMap<>(supplied.evidenceDetails())
        );
    }

    private InterviewProjectEvidence readProjectEvidence(InterviewSessionEntity entity, ResumeView resume) {
        InterviewProjectEvidence stored = jsonCodec.read(entity.getProjectEvidenceJson(), InterviewProjectEvidence.class);
        if (stored != null) return stored;
        if (resume == null) return null;
        return projectEvidence(null, resume);
    }

    private String preferred(String value, String fallback) {
        String clean = safe(value).trim();
        return clip(clean.isBlank() ? safe(fallback) : clean, 1200);
    }

    private JobInterviewBlueprint readJobBlueprint(InterviewSessionEntity entity) {
        return jsonCodec.read(entity.getJobBlueprintJson(), JobInterviewBlueprint.class);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record Evaluation(int score, String feedback, String nextQuestion) {}

    public record AgentEvaluation(Integer score, String feedback, String nextQuestion) {}
}
