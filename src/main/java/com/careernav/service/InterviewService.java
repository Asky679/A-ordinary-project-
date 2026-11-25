package com.careernav.service;

import com.careernav.auth.AuthContext;
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
    private static final int TOTAL_ROUNDS = 10;
    private static final int FALLBACK_ROUNDS = 8;
    private static final String JUNIOR_SCORING_VERSION = "JUNIOR_ADAPTIVE_2026_11_V2";
    private static final String STANDARD_SCORING_VERSION = "STANDARD_ADAPTIVE_2026_11_V1";
    private static final Map<String, List<String>> MODE_PLANS = Map.of(
            "综合面", List.of("求职动机", "项目职责", "业务链路", "技术实现", "故障与取舍", "团队协作", "失败与学习", "候选人反问"),
            "技术面", List.of("个人背景", "项目职责", "业务链路", "技术实现", "故障与取舍", "结果验证", "技术判断", "候选人反问"),
            "HR面", List.of("个人背景", "求职动机", "项目概述", "团队协作", "冲突处理", "岗位意愿", "职业规划", "候选人反问"),
            "主管面", List.of("个人背景", "项目职责", "业务链路", "技术判断", "故障与取舍", "团队协作", "结果验证", "候选人反问"),
            "行为面", List.of("个人背景", "团队协作", "冲突处理", "失败与学习", "推动改变", "压力与优先级", "职业规划", "候选人反问")
    );
    private static final Set<String> TECHNICAL_STAGES = Set.of(
            "项目职责", "项目概述", "业务链路", "技术实现", "基础概念", "故障与取舍", "结果验证", "技术判断"
    );
    private final InterviewSessionRepository repository;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final JsonCodec jsonCodec;
    private final AgentModelClient agentModelClient;
    private final LangGraphInterviewClient langGraphClient;
    private final InterviewSkillEvidenceService skillEvidenceService;
    private final JobInterviewBlueprintService blueprintService;
    private final CandidateStoryService storyBankService;

    public InterviewService(InterviewSessionRepository repository, ResumeService resumeService,
                            JobService jobService, JsonCodec jsonCodec,
                            AgentModelClient agentModelClient, LangGraphInterviewClient langGraphClient,
                            InterviewSkillEvidenceService skillEvidenceService,
                            JobInterviewBlueprintService blueprintService,
                            CandidateStoryService storyBankService) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
        this.agentModelClient = agentModelClient;
        this.langGraphClient = langGraphClient;
        this.skillEvidenceService = skillEvidenceService;
        this.blueprintService = blueprintService;
        this.storyBankService = storyBankService;
    }

    @Transactional
    public InterviewView start(InterviewStartRequest request) {
        ResumeView resume = resumeService.get(request.resumeId());
        JobView job = jobService.get(request.jobId());
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "标准" : request.difficulty();
        String interviewMode = normalizeMode(request.interviewMode());
        InterviewProjectEvidence projectEvidence = projectEvidence(request.projectEvidence(), resume);
        JobInterviewBlueprint jobBlueprint = blueprintService.build(job, resume);
        List<CandidateStoryView> storyBank = storyBankService.loadForInterview(resume.id());
        List<InterviewMessage> messages = new ArrayList<>();
        messages.add(new InterviewMessage("assistant", firstQuestion(resume, job, difficulty, interviewMode, projectEvidence),
                LocalDateTime.now(), null, null));

        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setUserId(AuthContext.userIdOrNull());
        entity.setDataKind(AuthContext.dataKind());
        entity.setResumeId(resume.id());
        entity.setJobId(job.id());
        entity.setJobTitle(job.title());
        entity.setDifficulty(difficulty);
        entity.setInterviewMode(interviewMode);
        entity.setScoringVersion(isJuniorDifficulty(difficulty) ? JUNIOR_SCORING_VERSION : STANDARD_SCORING_VERSION);
        entity.setStatus("IN_PROGRESS");
        entity.setCurrentRound(1);
        boolean agentAvailable = langGraphClient.available();
        entity.setTotalRounds(agentAvailable ? TOTAL_ROUNDS : FALLBACK_ROUNDS);
        entity.setOverallScore(0);
        entity.setMessagesJson(jsonCodec.write(messages));
        entity.setProjectEvidenceJson(jsonCodec.write(projectEvidence));
        entity.setJobBlueprintJson(jsonCodec.write(jobBlueprint));
        entity.setStartedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getStartedAt());
        entity = repository.save(entity);

        if (agentAvailable) {
            LangGraphInterviewClient.AgentTurn turn = langGraphClient.start(entity.getId(), resume, job, difficulty, interviewMode, projectEvidence, jobBlueprint, storyBank);
            if (turn != null && turn.question() != null && !turn.question().isBlank()) {
                List<InterviewMessage> agentMessages = new ArrayList<>();
                agentMessages.add(new InterviewMessage("assistant", turn.question(), LocalDateTime.now(), null, null));
                entity.setMessagesJson(jsonCodec.write(agentMessages));
                entity.setCurrentRound(Math.max(1, turn.round()));
                entity.setTotalRounds(Math.max(1, turn.targetRounds()));
                entity.setUpdatedAt(LocalDateTime.now());
                entity = repository.save(entity);
            } else {
                entity.setTotalRounds(FALLBACK_ROUNDS);
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
        String interviewMode = storedMode(entity.getInterviewMode());

        if (langGraphClient.available()) {
            LangGraphInterviewClient.AgentTurn turn = langGraphClient.answer(id, answer, resume, job, interviewMode, projectEvidence, jobBlueprint);
            if (turn != null) {
                int score = turn.score() == null ? 0 : Math.max(0, Math.min(100, turn.score()));
                String feedback = turn.feedback() == null || turn.feedback().isBlank()
                        ? "已完成本轮回答评估。" : turn.feedback();
                messages.add(new InterviewMessage("user", answer.trim(), LocalDateTime.now(), score, feedback));

                int answered = entity.getCurrentRound();
                int oldTotal = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
                entity.setOverallScore((int) Math.round(
                        (oldTotal * (answered - 1) + score) / (double) answered));
                entity.setTotalRounds(Math.max(1, turn.targetRounds()));

                if (turn.done() || answered >= entity.getTotalRounds()) {
                    entity.setStatus("COMPLETED");
                    entity.setTotalRounds(turn.done() ? answered : entity.getTotalRounds());
                    entity.setCurrentRound(entity.getTotalRounds());
                    boolean technicalAssessment = assessesTechnicalSkills(interviewMode);
                    if (technicalAssessment) saveSkillEvidence(entity, resume, job, messages);
                    storyBankService.ingestCompletedSession(entity, resume, job, messages);
                    messages.add(new InterviewMessage("assistant",
                            technicalAssessment
                                    ? "本场模拟面试已结束。面试官已根据回答、岗位要求和会话记录整理技能证据。"
                                    : "本场" + interviewMode + "已结束。面试官已记录你的沟通、动机、协作和反思表现。",
                            LocalDateTime.now(), null, null));
                } else {
                    entity.setCurrentRound(Math.max(answered + 1, turn.round()));
                    String next = turn.question();
                    if (next == null || next.isBlank()) {
                        next = nextQuestion(entity.getCurrentRound(), interviewMode, resume, job, projectEvidence, entity.getDifficulty());
                    }
                    messages.add(new InterviewMessage("assistant", next, LocalDateTime.now(), null, null));
                }
                entity.setMessagesJson(jsonCodec.write(messages));
                entity.setUpdatedAt(LocalDateTime.now());
                return toView(repository.save(entity));
            }
        }

        int fallbackTotalRounds = Math.min(
                entity.getTotalRounds() == null ? FALLBACK_ROUNDS : entity.getTotalRounds(),
                FALLBACK_ROUNDS);
        Evaluation evaluation = evaluate(answer, job, resume, projectEvidence, interviewMode,
                entity.getCurrentRound(), fallbackTotalRounds, entity.getDifficulty());
        messages.add(new InterviewMessage("user", answer.trim(), LocalDateTime.now(),
                evaluation.score(), evaluation.feedback()));

        int answered = entity.getCurrentRound();
        int oldTotal = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
        entity.setOverallScore((int) Math.round(
                (oldTotal * (answered - 1) + evaluation.score()) / (double) answered));

        if (answered >= fallbackTotalRounds) {
            entity.setStatus("COMPLETED");
            entity.setTotalRounds(fallbackTotalRounds);
            boolean technicalAssessment = assessesTechnicalSkills(interviewMode);
            if (technicalAssessment) saveSkillEvidence(entity, resume, job, messages);
            storyBankService.ingestCompletedSession(entity, resume, job, messages);
            messages.add(new InterviewMessage("assistant",
                    technicalAssessment
                            ? "本场模拟面试已结束。面试官已根据回答、岗位要求和会话记录整理技能证据。"
                            : "本场" + interviewMode + "已结束。面试官已记录你的沟通、动机、协作和反思表现。",
                    LocalDateTime.now(), null, null));
        } else {
            entity.setCurrentRound(answered + 1);
            String next = evaluation.nextQuestion();
            if (next == null || next.isBlank()) {
                next = nextQuestion(entity.getCurrentRound(), interviewMode, resume, job, projectEvidence, entity.getDifficulty());
            }
            messages.add(new InterviewMessage("assistant", next, LocalDateTime.now(), null, null));
        }
        entity.setMessagesJson(jsonCodec.write(messages));
        entity.setUpdatedAt(LocalDateTime.now());
        return toView(repository.save(entity));
    }

    @Transactional
    public InterviewView undo(Long id) {
        InterviewSessionEntity entity = getEntity(id);
        if (entity.getDeletedAt() != null) throw new BusinessException("面试记录已删除，无法撤回");
        List<InterviewMessage> messages = new ArrayList<>(
                jsonCodec.readList(entity.getMessagesJson(), InterviewMessage.class));
        int lastUser = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role())) {
                lastUser = index;
                break;
            }
        }
        if (lastUser < 0) throw new BusinessException("还没有可撤回的回答");

        if (langGraphClient.available() && !langGraphClient.undo(id)) {
            throw new BusinessException("面试 Agent 会话撤回失败，请稍后重试");
        }

        boolean wasCompleted = "COMPLETED".equals(entity.getStatus());
        List<InterviewMessage> remaining = new ArrayList<>(messages.subList(0, lastUser));
        if (remaining.isEmpty() || !"assistant".equals(remaining.get(remaining.size() - 1).role())) {
            throw new BusinessException("面试记录状态异常，无法恢复到上一题");
        }

        int answered = 0;
        int scoreSum = 0;
        for (InterviewMessage message : remaining) {
            if (!"user".equals(message.role())) continue;
            answered++;
            if (message.score() != null) scoreSum += message.score();
        }
        entity.setCurrentRound(answered + 1);
        entity.setTotalRounds(Math.max(entity.getCurrentRound(), entity.getTotalRounds() == null ? TOTAL_ROUNDS : entity.getTotalRounds()));
        entity.setOverallScore(answered == 0 ? 0 : (int) Math.round(scoreSum / (double) answered));
        entity.setStatus("IN_PROGRESS");
        entity.setSkillEvidenceJson(null);
        entity.setMessagesJson(jsonCodec.write(remaining));
        entity.setUpdatedAt(LocalDateTime.now());
        if (wasCompleted) storyBankService.removeForSession(id);
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
        Long userId = AuthContext.userIdOrNull();
        List<InterviewSessionEntity> rows = userId == null || AuthContext.isAdmin()
                ? repository.findByDeletedAtIsNullOrderByUpdatedAtDesc()
                : repository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
        return rows.stream().map(this::toView).toList();
    }

    @Transactional
    public void delete(Long id) {
        InterviewSessionEntity entity = getEntity(id);
        if (entity.getDeletedAt() == null) {
            entity.setDeletedAt(LocalDateTime.now());
            repository.save(entity);
        }
    }

    @Transactional(readOnly = true)
    public List<InterviewView> trash() {
        Long userId = AuthContext.userIdOrNull();
        List<InterviewSessionEntity> rows = userId == null || AuthContext.isAdmin()
                ? repository.findByDeletedAtIsNotNullOrderByDeletedAtDesc()
                : repository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(userId);
        return rows.stream().map(this::toView).toList();
    }

    @Transactional
    public void restore(Long id) {
        InterviewSessionEntity entity = getEntity(id);
        entity.setDeletedAt(null);
        repository.save(entity);
    }

    @Transactional
    public void permanentDelete(Long id) {
        repository.delete(getEntity(id));
    }

    @Transactional
    public int clearAll() {
        Long userId = AuthContext.userIdOrNull();
        List<InterviewSessionEntity> sessions = userId == null || AuthContext.isAdmin()
                ? repository.findByDeletedAtIsNullOrderByUpdatedAtDesc()
                : repository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
        LocalDateTime now = LocalDateTime.now();
        for (InterviewSessionEntity session : sessions) session.setDeletedAt(now);
        repository.saveAll(sessions);
        return sessions.size();
    }

    @Transactional
    public int emptyTrash() {
        Long userId = AuthContext.userIdOrNull();
        List<InterviewSessionEntity> sessions = userId == null || AuthContext.isAdmin()
                ? repository.findByDeletedAtIsNotNullOrderByDeletedAtDesc()
                : repository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(userId);
        repository.deleteAll(sessions);
        return sessions.size();
    }

    @Transactional(readOnly = true)
    public long count() {
        Long userId = AuthContext.userIdOrNull();
        return userId == null || AuthContext.isAdmin()
                ? repository.countByDeletedAtIsNull()
                : repository.countByUserIdAndDeletedAtIsNull(userId);
    }

    private InterviewSessionEntity getEntity(Long id) {
        InterviewSessionEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException("面试记录不存在：" + id));
        if (!AuthContext.canAccess(entity.getUserId())) throw new BusinessException("无权访问这条面试记录");
        return entity;
    }

    private String firstQuestion(ResumeView resume, JobView job, String difficulty,
                                 InterviewProjectEvidence evidence) {
        String question = questionForStage("项目职责", resume, job, evidence, difficulty);
        return question + " 只需要讲脱敏模块和业务，不需要提供源码、ZIP 或公司敏感信息。";
    }
    private String firstQuestion(ResumeView resume, JobView job, String difficulty,
                                 String interviewMode, InterviewProjectEvidence evidence) {
        String stage = plannedStage(interviewMode, 1);
        String fallback = questionForStage(stage, resume, job, evidence, difficulty);
        String aiQuestion = agentModelClient.generate("""
                你是计算机岗位模拟面试官。请生成本场面试的第一问。
                面试类型：%s
                本轮固定维度：%s
                岗位：%s / %s
                岗位技能：%s
                候选人目标：%s
                脱敏项目证据卡：%s
                面试强度：%s

                要求：
                1. 只问本轮固定维度的问题，不得把 HR、动机、协作或行为问题改写成技术八股。
                2. 第一问只问一个主要问题，控制在 100 字以内。
                3. 不要求源码、ZIP、公司内部数据或客户信息。
                4. 如果面试强度含“初级/实习/校招/应届”，问题必须短、具体，只问个人实现、输入输出、边界、测试和调试；不要默认问高并发架构或组织级治理。
                5. 只输出面试官要说的话，不要解释规则。
                """.formatted(interviewMode, stage, job.title(), safe(job.company()),
                String.join("、", job.skills()), resume.targetRole(), jsonCodec.write(evidence), difficulty));
        return aiQuestion == null || aiQuestion.isBlank() ? fallback : clip(aiQuestion, 500);
    }

    private String nextQuestion(int round, String interviewMode, ResumeView resume, JobView job,
                                InterviewProjectEvidence evidence, String difficulty) {
        return questionForStage(plannedStage(interviewMode, round), resume, job, evidence, difficulty);
    }

    private String questionForStage(String stage, ResumeView resume, JobView job,
                                    InterviewProjectEvidence evidence, String difficulty) {
        String project = evidence == null || safe(evidence.projectName()).isBlank()
                ? (resume.projects().isEmpty() ? "你的代表项目" : safe(resume.projects().getFirst().name()))
                : safe(evidence.projectName());
        String role = evidence == null || safe(evidence.role()).isBlank()
                ? "项目成员" : safe(evidence.role());
        Map<String, String> evidenceDetails = evidence != null && evidence.evidenceDetails() != null
                ? evidence.evidenceDetails() : Map.of();
        String owned = safe(evidenceDetails.get("ownedModules"));
        if (owned.isBlank()) owned = "你独立负责的模块";
        String excluded = safe(evidenceDetails.get("notOwnedModules"));
        String skill = job.skills().stream().filter(item -> item != null && !item.isBlank())
                .findFirst().orElse("岗位核心技能");
        String responsibility = job.responsibilities() == null || job.responsibilities().isEmpty()
                ? "岗位核心职责" : safe(job.responsibilities().getFirst());
        String company = safe(job.company()).isBlank() ? "目标公司" : safe(job.company());
        String title = safe(job.title()).isBlank() ? "目标岗位" : safe(job.title());
        String city = safe(resume.city()).isBlank() ? "目标城市" : safe(resume.city());
        if (isJuniorDifficulty(difficulty)) {
            return juniorQuestionForStage(stage, inferJobDirection(job, resume), project, role, owned, skill, company, title, city, responsibility);
        }
        return switch (stage) {
            case "求职动机" -> "你为什么选择“" + title + "”这个方向？结合你目前的技术积累，说明这个岗位最吸引你的一点，以及你希望入职后前六个月学到什么。";
            case "个人背景" -> "请用两分钟介绍你的技术方向和一个与“" + title + "”最相关的项目，重点说明你具体负责什么、做出了什么结果。";
            case "项目概述" -> "请用两分钟介绍“" + project + "”：项目解决什么问题、团队如何分工、你本人负责哪一部分，以及目前完成到什么程度。";
            case "项目职责" -> "我们围绕你简历里的“" + project + "”聊聊。你担任" + role + "，请说明团队规模、你独立完成的模块、哪些工作由别人负责，以及你能决定到什么程度。";
            case "业务链路" -> "请从“" + project + "”中选一条最核心的业务链路，按用户入口、服务处理、数据变化和返回结果讲清楚，并指出你负责的环节。";
            case "技术实现" -> "请说明“" + skill + "”在“" + project + "”中的具体落点：核心接口、数据结构、异常处理和“" + owned + "”分别是什么。";
            case "故障与取舍" -> "请复盘“" + project + "”中的一个真实故障或高风险问题，说明你如何定位、为什么采用当时的方案、牺牲了什么，以及如何验证修复有效。";
            case "结果验证" -> "请说明“" + project + "”的结果证据：基线是什么、怎么测试、哪些结果是你个人贡献，哪些只是团队或流程验证。";
            case "技术判断" -> "结合岗位职责“" + responsibility + "”，如果项目中有一个要求你没有直接做过，你会如何迁移已有经验、验证可行性并控制风险？";
            case "团队协作" -> "在“" + project + "”里，讲一次你和队友分工不一致、意见冲突或需要互相补位的经历。你具体如何沟通，最后怎样推进？";
            case "冲突处理" -> "讲一次你的方案或工作被同学、老师或队友质疑的经历。你当时如何回应、如何判断谁对，最后结果是什么？";
            case "失败与学习" -> "讲一次你在学习或项目中失败、返工或判断错误的经历。根因是什么，你后来改变了什么做法，之后有没有验证改进？";
            case "岗位意愿" -> "你为什么选择“" + company + "”的“" + title + "”？请同时说明你对工作地点“" + city + "”、到岗时间和工作安排的接受程度。";
            case "职业规划" -> "未来两到三年，你希望成为什么方向的工程师？这个岗位在你的成长路径中承担什么作用？";
            case "推动改变" -> "讲一次你发现团队流程或项目做法效率不高，并主动推动改变的经历。你如何争取支持，遇到了什么阻力？";
            case "压力与优先级" -> "当多个任务同时压来、时间不够时，你通常如何排优先级？请用一个真实经历说明你放弃了什么、保证了什么。";
            case "候选人反问" -> "本场面试接近尾声。你有什么问题想反问面试官？可以问岗位职责、团队协作、成长反馈或技术环境。";
            default -> "请结合“" + project + "”和岗位职责“" + responsibility + "”，说明一个最能证明你能力的真实经历。";
        };
    }


    private boolean isJuniorDifficulty(String difficulty) {
        String value = safe(difficulty).toLowerCase(Locale.ROOT);
        return value.contains("初级") || value.contains("实习") || value.contains("校招")
                || value.contains("应届") || value.contains("junior");
    }

    private String juniorQuestionForStage(String stage, String direction, String project, String role, String owned,
                                           String skill, String company, String title, String city,
                                           String responsibility) {
        String question = switch (stage) {
            case "求职动机" -> "为什么选择这个技术方向？现在最熟悉和最不熟悉的部分分别是什么？";
            case "个人背景" -> "先用两分钟介绍你的技术方向和一个最相关的项目，重点说你本人负责什么。";
            case "项目概述" -> "介绍一下“" + project + "”解决什么问题、团队有谁、你负责哪一块，以及目前做到什么程度。";
            case "项目职责" -> "“" + project + "”里哪一部分是你亲手做的？挑一个接口、页面、脚本或测试用例说清楚。";
            case "业务链路" -> "从用户入口开始，这个请求经过哪些模块，你负责的是哪一段？";
            case "技术实现" -> "“" + skill + "”在“" + project + "”里具体用在哪？如果参数为空、重复提交或超时，你会怎么处理？";
            case "基础概念" -> "请用一两句话说明“" + skill + "”的核心概念，再结合你的项目说你实际在哪里用到。";
            case "故障与取舍", "故障与边界" -> "讲一个你在“" + project + "”里真实遇到的 Bug，说明现象、复现、排查和修复验证。";
            case "结果验证" -> "你怎么证明“" + project + "”的功能真的可用？测了哪些正常和边界用例？";
            case "方案取舍", "技术判断" -> "如果岗位要求里有一项你没做过，你会先怎么确认自己能不能做，并控制风险？";
            case "团队协作" -> "在“" + project + "”里，讲一次你和队友分工或接口不一致的经历，你具体怎么推进？";
            case "冲突处理" -> "讲一次你的方案被同学、老师或队友质疑的经历，你当时怎么判断和处理？";
            case "失败与学习" -> "讲一次你失败、返工或判断错误的经历，根因是什么，后来改了什么？";
            case "岗位意愿" -> "你为什么选择“" + company + "”的“" + title + "”，以及你对“" + city + "”和工作时间能接受到什么程度？";
            case "职业规划" -> "未来一年你最想补强哪项基础能力？为什么？";
            case "推动改变" -> "讲一次你发现团队流程或项目做法效率不高，并主动推动改变的经历，你具体做了什么？";
            case "压力与优先级" -> "多个任务时间冲突时，你通常怎么排优先级？举一个真实例子。";
            case "候选人反问" -> "你有什么问题想反问面试官？可以问岗位职责、团队协作、成长反馈或技术环境。";
            default -> "请结合“" + project + "”和岗位职责“" + responsibility + "”，说明你亲手做过的一部分。";
        };
        return juniorDirectionalize(question, stage, direction);
    }

    private String inferJobDirection(JobView job, ResumeView resume) {
        String title = safe(job.title()).toLowerCase(Locale.ROOT);
        String combined = title + " " + String.join(" ", job.skills()).toLowerCase(Locale.ROOT)
                + " " + safe(resume.targetRole()).toLowerCase(Locale.ROOT);
        if (containsAny(title, "前端", "frontend", "web前端")) return "前端";
        if (containsAny(title, "测试", "qa", "测开", "自动化测试")) return "测试";
        if (containsAny(title, "运维", "devops", "sre")) return "运维";
        if (containsAny(title, "数据", "算法", "机器学习", "大模型", "ai", "llm")) return "数据/AI";
        if (containsAny(title, "嵌入式", "单片机", "c++", "驱动", "固件")) return "嵌入式/C++";
        if (containsAny(title, "安全", "渗透", "漏洞", "攻防")) return "网络安全";
        if (containsAny(title, "golang", "go开发", "go后端")) return "Go后端";
        if (containsAny(title, "java", "后端", "backend")) return "Java后端";
        if (containsAny(combined, "vue", "react", "javascript", "typescript", "css", "html")) return "前端";
        if (containsAny(combined, "selenium", "接口测试", "自动化测试")) return "测试";
        if (containsAny(combined, "docker", "kubernetes", "nginx", "devops")) return "运维";
        if (containsAny(combined, "pytorch", "tensorflow", "机器学习", "深度学习")) return "数据/AI";
        if (containsAny(combined, "c++", "stm32", "rtos", "单片机")) return "嵌入式/C++";
        if (containsAny(combined, "owasp", "渗透", "sql注入", "漏洞")) return "网络安全";
        if (containsAny(combined, "golang", " go ", "go语言")) return "Go后端";
        return "Java后端";
    }

    private String juniorDirectionalize(String question, String stage, String direction) {
        if (!Set.of("项目职责", "业务链路", "技术实现", "基础概念", "故障与取舍", "故障与边界",
                "方案取舍", "技术判断", "结果验证", "证据补强", "迁移验证").contains(stage)) return question;
        String hint = switch (direction) {
            case "前端" -> "请围绕页面状态、组件、接口请求、异常渲染和浏览器验证说明。";
            case "测试" -> "请围绕测试用例、缺陷复现、断言和回归验证说明。";
            case "运维" -> "请围绕部署、端口、配置、日志、监控和回滚说明。";
            case "数据/AI" -> "请围绕数据清洗、指标口径、模型训练和结果评估说明。";
            case "嵌入式/C++" -> "请围绕输入输出、内存资源、硬件联调和日志验证说明。";
            case "网络安全" -> "请围绕授权范围、漏洞复现、影响判断和修复复测说明。";
            case "Go后端" -> "请围绕 Handler、goroutine、channel、超时和并发验证说明。";
            default -> "请围绕 Controller、Service、Mapper、事务、SQL 和日志排查说明。";
        };
        return question + " " + hint;
    }

    private boolean containsAny(String text, String... terms) {
        String value = safe(text).toLowerCase(Locale.ROOT);
        for (String term : terms) if (value.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String storedMode(String mode) {
        return safe(mode).isBlank() ? "技术面" : normalizeMode(mode);
    }
    private String normalizeMode(String mode) {
        String value = safe(mode).trim();
        return MODE_PLANS.containsKey(value) ? value : "综合面";
    }

    private String plannedStage(String mode, int round) {
        List<String> plan = MODE_PLANS.getOrDefault(normalizeMode(mode), MODE_PLANS.get("综合面"));
        int index = Math.max(0, Math.min(plan.size() - 1, round - 1));
        return plan.get(index);
    }

    private boolean technicalStage(String stage) {
        return TECHNICAL_STAGES.contains(stage);
    }
    private Evaluation evaluate(String answer, JobView job, ResumeView resume,
                                InterviewProjectEvidence evidence, String interviewMode,
                                int currentRound, int totalRounds, String difficulty) {
        String text = answer.trim();
        if (isJuniorDifficulty(difficulty)) {
            return evaluateJunior(answer, job, resume, evidence, interviewMode, currentRound, totalRounds, difficulty);
        }
        String currentStage = plannedStage(interviewMode, currentRound);
        boolean technical = technicalStage(currentStage);
        int score = 35;
        List<String> signals = new ArrayList<>();
        if (text.length() >= 80) { score += 6; signals.add("内容较完整"); }
        if (text.length() >= 180) { score += 5; signals.add("细节充分"); }
        List<String> structureWords = List.of("首先", "其次", "最后", "背景", "任务", "行动", "结果", "star", "当时");
        long structureHits = structureWords.stream().filter(text.toLowerCase(Locale.ROOT)::contains).count();
        if (structureHits >= 2) { score += 12; signals.add("表达有结构"); }
        if (technical) {
            long techHits = job.skills().stream()
                    .filter(skill -> text.toLowerCase(Locale.ROOT).contains(skill.toLowerCase(Locale.ROOT))).count();
            score += (int) Math.min(15, techHits * 4);
            if (techHits > 0) signals.add("技术关键词命中");
        } else {
            long behaviorHits = List.of("沟通", "协调", "冲突", "推进", "反思", "改进", "学习", "取舍", "结果", "复盘")
                    .stream().filter(text::contains).count();
            score += (int) Math.min(15, behaviorHits * 3);
            if (behaviorHits >= 2) signals.add("有具体行为和反思");
        }
        if (text.matches(".*\\d+.*")) { score += 5; signals.add("使用了数据或结果证据"); }
        if (List.of("我负责", "我主导", "我设计", "我实现", "我决定", "我协调", "我推动")
                .stream().anyMatch(text::contains)) {
            score += 8;
            signals.add("说明了个人行动");
        }
        score = Math.max(0, Math.min(92, score));
        String feedback = signals.isEmpty()
                ? "回答方向正确，但信息密度偏低。建议按“情境—任务—行动—结果—反思”补充具体事实。"
                : "表现亮点：" + String.join("、", signals) + "。建议继续补充判断依据、个人行动和可验证结果。";
        String fallbackNext = currentRound >= totalRounds
                ? null : nextQuestion(currentRound + 1, interviewMode, resume, job, evidence, difficulty);

        String projects = resume.projects().stream().limit(2)
                .map(item -> item.name() + "：" + safe(item.description()) + "；成果：" + safe(item.highlight()))
                .collect(Collectors.joining("\n"));
        String nextStage = currentRound >= totalRounds ? "finish" : plannedStage(interviewMode, currentRound + 1);
        AgentEvaluation ai = agentModelClient.generateJson("""
                你是严格但友善的计算机岗位面试官。当前是一场“%s”模拟面试。
                岗位：%s
                岗位要求：%s
                候选人技能自述：%s
                候选人项目自述：%s
                当前轮次：%d / %d
                本轮已问维度：%s
                下一轮必须进入的维度：%s

                评分要符合本轮维度：
                - 技术、项目、故障、验证类问题重点看技术正确性、个人贡献、取舍和验证证据。
                - 如果面试强度含“初级/实习/校招/应届”，要面向刚毕业学生：没有生产经验不重罚；65-74 表示有培养潜力，75-84 表示符合初级基本要求，85 以上表示证据充分。
                - 动机、协作、冲突、失败、规划类问题重点看真实经历、行动、沟通、反思和结果，不能因为没有技术关键词就扣分。
                不能仅因回答长或关键词多就给高分，也不得编造候选人没有说过的经历。
                候选人回答：
                <<<%s>>>

                返回 JSON：
                {"score":0到100的整数,"feedback":"指出正确点、缺失证据或表达问题，100字以内","nextQuestion":"严格对应下一轮维度的单个问题；已是最后一轮则返回空字符串"}
                """.formatted(interviewMode, job.title(), String.join("、", job.skills()),
                String.join("、", resume.skills()), projects.isBlank() ? "暂无项目" : projects,
                currentRound, totalRounds, currentStage, nextStage, text), AgentEvaluation.class);

        if (ai != null && ai.score() != null) {
            int aiScore = Math.max(0, Math.min(100, ai.score()));
            score = isJuniorDifficulty(difficulty)
                    ? Math.max(score, Math.min(94, aiScore))
                    : (score + aiScore) / 2;
            if (ai.feedback() != null && !ai.feedback().isBlank()) {
                feedback = clip(ai.feedback(), 360);
            }
            if (ai.nextQuestion() != null && !ai.nextQuestion().isBlank()) {
                fallbackNext = clip(ai.nextQuestion(), 360);
            }
        }
        return new Evaluation(score, feedback, fallbackNext);
    }

    private Evaluation evaluateJunior(String answer, JobView job, ResumeView resume,
                                      InterviewProjectEvidence evidence, String interviewMode,
                                      int currentRound, int totalRounds, String difficulty) {
        String text = answer.trim();
        String stage = plannedStage(interviewMode, currentRound);
        boolean technical = technicalStage(stage);
        int score = 44;
        List<String> signals = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<String> relevanceHits = List.of(
                "项目", "接口", "服务", "数据库", "测试", "用例", "用户", "代码", "日志",
                "Controller", "Service", "PostgreSQL", "Qdrant", "LangGraph", "Java",
                "Spring", "MyBatis", "Redis", "MySQL", "Linux", "API", "部署"
        ).stream().filter(text::contains).toList();
        if (text.length() >= 50 && relevanceHits.size() >= 2) score += 8;
        else if (text.length() >= 35 && !relevanceHits.isEmpty()) score += 5;
        List<String> personalTerms = List.of(
                "我负责", "我写", "我改", "我实现", "我测试", "我查", "我定位", "我修复",
                "我选择", "我遇到", "我做", "我熟悉", "我学过", "我使用"
        );
        boolean hasPersonal = personalTerms.stream().anyMatch(text::contains);
        if (hasPersonal) {
            score += 10;
            signals.add("说明了个人行动");
        } else {
            gaps.add("个人贡献不够明确");
        }
        if (technical) {
            boolean hasBoundary = List.of("空值", "为空", "null", "重复", "重复请求", "超时", "异常", "边界", "失败", "不存在", "查不到", "没查到", "返回错误", "越权", "回滚")
                    .stream().anyMatch(text::contains);
            if (hasBoundary) {
                score += 8;
                signals.add("考虑了边界或异常");
            } else {
                gaps.add("缺少边界或异常处理");
            }
            boolean hasVerification = List.of("测试", "测了", "跑过", "验证", "验证过", "复现", "回归", "回归过", "日志", "断言", "预期", "实际", "用例")
                    .stream().anyMatch(text::contains);
            if (hasVerification) {
                score += 10;
                signals.add("有测试或验证过程");
            } else {
                gaps.add("没有说明如何验证");
            }
            if (List.of("排查", "定位", "根因", "日志", "报错", "现象").stream().anyMatch(text::contains)) {
                score += 8;
                signals.add("描述了排查过程");
            }
            if (text.matches(".*\\d+.*") || List.of("截图", "测试记录", "接口返回", "日志").stream().anyMatch(text::contains)) {
                score += 6;
                signals.add("给出了可核验证据");
            }
            long skillHits = job.skills().stream().filter(skill -> text.toLowerCase(Locale.ROOT)
                    .contains(skill.toLowerCase(Locale.ROOT))).count();
            if (skillHits > 0) {
                score += 8;
                signals.add("使用了岗位相关技术");
            }
            if (text.length() >= 40) score += 4;
            if (text.length() >= 100) score += 4;
            boolean vagueArchitecture = List.of("高并发", "分布式", "微服务", "架构设计").stream().anyMatch(text::contains)
                    && !List.of("测试", "验证", "复现", "日志", "边界", "指标").stream().anyMatch(text::contains);
            if (vagueArchitecture) {
                score -= 5;
                gaps.add("出现架构类词但缺少具体证据");
            }
            if (!hasVerification) score = Math.min(score, 84);
            if (!hasPersonal) score = Math.min(score, 78);
        } else {
            long behaviorHits = List.of("沟通", "协调", "冲突", "推进", "反思", "改进", "学习", "复盘", "结果")
                    .stream().filter(text::contains).count();
            if (behaviorHits > 0) {
                score += 10;
                signals.add("有具体经历、行动或反思");
            } else {
                gaps.add("缺少具体经历或反思");
            }
            if (text.length() >= 60) score += 6;
            if (List.of("不会", "没做过", "还没", "正在学", "最不熟悉").stream().anyMatch(text::contains)) {
                score += 5;
                signals.add("坦诚说明当前能力边界");
            }
        }
        if (List.of("不会", "没做过", "不了解", "不是我负责", "队友负责", "只了解", "没有数据")
                .stream().anyMatch(text::contains)) {
            score += 5;
            signals.add("说明了能力或责任边界");
        }
        if (text.length() >= 50 && relevanceHits.size() >= 2) {
            score = Math.max(score, 65);
        } else if (text.length() >= 35 && !relevanceHits.isEmpty() && hasPersonal) {
            score = Math.max(score, 62);
        }
        score = Math.max(45, Math.min(94, score));
        String feedback = signals.isEmpty()
                ? "回答方向可以，但需要补充具体个人行动和验证证据"
                : "表现亮点：" + String.join("、", signals);
        if (!gaps.isEmpty()) feedback += "。建议补充：" + String.join("、", gaps.subList(0, Math.min(3, gaps.size())));
        String fallbackNext = currentRound >= totalRounds
                ? null : nextQuestion(currentRound + 1, interviewMode, resume, job, evidence, difficulty);
        return new Evaluation(score, feedback, fallbackNext);
    }

    private boolean assessesTechnicalSkills(String interviewMode) {
        return !"HR面".equals(interviewMode) && !"行为面".equals(interviewMode);
    }
    private void saveSkillEvidence(InterviewSessionEntity entity, ResumeView resume, JobView job,
                                    List<InterviewMessage> messages) {
        int overall = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
        List<InterviewSkillEvidence> evidence = skillEvidenceService.assess(resume, job, messages, overall,
                readProjectEvidence(entity, resume), readJobBlueprint(entity));
        entity.setSkillEvidenceJson(jsonCodec.write(evidence));
    }

    private InterviewView toView(InterviewSessionEntity entity) {
        int overallScore = entity.getOverallScore() == null ? 0 : entity.getOverallScore();
        String scoringVersion = entity.getScoringVersion();
        boolean scoreComparable = scoringVersion != null && !scoringVersion.startsWith("LEGACY");
        String effectiveVersion = scoringVersion == null ? "LEGACY_PRE_2026_11" : scoringVersion;
        return new InterviewView(entity.getId(), entity.getResumeId(), entity.getJobId(), entity.getJobTitle(),
                storedMode(entity.getInterviewMode()),
                entity.getDifficulty(), entity.getStatus(),
                entity.getCurrentRound() == null ? 0 : entity.getCurrentRound(),
                entity.getTotalRounds() == null ? TOTAL_ROUNDS : entity.getTotalRounds(),
                overallScore, effectiveVersion, entity.getLegacyOverallScore(), scoreComparable,
                scoreBand(overallScore),
                jsonCodec.readList(entity.getMessagesJson(), InterviewMessage.class),
                jsonCodec.readList(entity.getSkillEvidenceJson(), InterviewSkillEvidence.class),
                readProjectEvidence(entity, null),
                readJobBlueprint(entity),
                entity.getDataKind() == null ? "LEGACY" : entity.getDataKind(),
                entity.getStartedAt(), entity.getUpdatedAt(), entity.getDeletedAt());
    }

    private String scoreBand(int score) {
        if (score >= 85) return "证据充分";
        if (score >= 75) return "符合初级基本要求";
        if (score >= 65) return "有培养潜力";
        if (score >= 55) return "方向相关但证据不足";
        return "需要系统补强";
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
