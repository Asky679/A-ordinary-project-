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
    private final InterviewSessionRepository repository;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final JsonCodec jsonCodec;
    private final AgentModelClient agentModelClient;

    public InterviewService(InterviewSessionRepository repository, ResumeService resumeService,
                            JobService jobService, JsonCodec jsonCodec,
                            AgentModelClient agentModelClient) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
        this.agentModelClient = agentModelClient;
    }

    @Transactional
    public InterviewView start(InterviewStartRequest request) {
        ResumeView resume = resumeService.get(request.resumeId());
        JobView job = jobService.get(request.jobId());
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "标准" : request.difficulty();
        List<InterviewMessage> messages = new ArrayList<>();
        messages.add(new InterviewMessage("assistant", firstQuestion(resume, job, difficulty),
                LocalDateTime.now(), null, null));

        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setResumeId(resume.id());
        entity.setJobId(job.id());
        entity.setJobTitle(job.title());
        entity.setDifficulty(difficulty);
        entity.setStatus("IN_PROGRESS");
        entity.setCurrentRound(1);
        entity.setTotalRounds(4);
        entity.setOverallScore(0);
        entity.setMessagesJson(jsonCodec.write(messages));
        entity.setStartedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getStartedAt());
        return toView(repository.save(entity));
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
            messages.add(new InterviewMessage("assistant",
                    "本场模拟面试结束。AI 面试官已将回答中的技术点和表达问题整理进反馈。下一步请把可量化结果补进简历，并重新运行 Agent 简历诊断。",
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

    private String firstQuestion(ResumeView resume, JobView job, String difficulty) {
        String fallback = String.format(
                "你好，欢迎参加“%s”岗位模拟面试，本轮强度为%s。我是你的 Agent 面试官。请先用 2 分钟介绍自己，重点说明你与%s相关的技能和一个代表项目。",
                job.title(), difficulty,
                String.join("、", job.skills().stream().limit(3).toList()));
        String aiQuestion = agentModelClient.generate("""
                你是计算机校园招聘的专业面试官。请根据以下信息生成第一个面试问题。
                岗位：%s
                岗位技能：%s
                候选人目标：%s
                代表项目：%s
                面试强度：%s

                要求：
                1. 先自然问候，再提出一个开放式自我介绍问题。
                2. 明确要求候选人用具体项目或成果证明能力。
                3. 只输出面试官要说的话，控制在 120 字以内，不要解释生成过程。
                """.formatted(job.title(), String.join("、", job.skills()), resume.targetRole(),
                resume.projects().isEmpty() ? "暂无项目" : resume.projects().getFirst().name(), difficulty));
        return aiQuestion == null ? fallback : clip(aiQuestion, 500);
    }

    private String nextQuestion(int round, ResumeView resume, JobView job) {
        List<String> missing = job.skills().stream()
                .filter(skill -> resume.skills().stream().noneMatch(owned -> owned.equalsIgnoreCase(skill)))
                .toList();
        return switch (round) {
            case 2 -> {
                String project = resume.projects().isEmpty() ? "你最有代表性的项目" : resume.projects().getFirst().name();
                yield "请深入介绍“" + project + "”。重点讲清楚你的职责、关键技术选型、遇到的难点以及最终结果。";
            }
            case 3 -> {
                String skill = missing.isEmpty() ? job.skills().getFirst() : missing.getFirst();
                yield "岗位要求掌握 " + skill + "。请结合场景说明你会如何应用它，以及如何排查线上异常。";
            }
            default -> "请讲一次团队协作中你推动问题解决的经历。你如何拆解任务、协调分歧，并通过数据验证结果？";
        };
    }

    private Evaluation evaluate(String answer, JobView job, ResumeView resume,
                                int currentRound, int totalRounds) {
        String text = answer.trim();
        int score = 48;
        List<String> signals = new ArrayList<>();
        if (text.length() >= 80) { score += 10; signals.add("内容较完整"); }
        if (text.length() >= 180) { score += 8; signals.add("细节充分"); }
        List<String> structureWords = List.of("首先", "其次", "最后", "背景", "任务", "行动", "结果", "star");
        long structureHits = structureWords.stream().filter(text.toLowerCase(Locale.ROOT)::contains).count();
        if (structureHits >= 2) { score += 12; signals.add("表达有结构"); }
        long techHits = job.skills().stream()
                .filter(skill -> text.toLowerCase(Locale.ROOT).contains(skill.toLowerCase(Locale.ROOT))).count();
        score += (int) Math.min(15, techHits * 4);
        if (techHits > 0) signals.add("技术关键词命中");
        if (text.matches(".*\\d+.*")) { score += 7; signals.add("使用了数据证据"); }
        score = Math.max(45, Math.min(96, score));
        String feedback = signals.isEmpty()
                ? "回答方向正确，但信息密度偏低。建议使用 STAR 结构，并补充技术选型、个人动作和量化结果。"
                : "表现亮点：" + String.join("、", signals) + "。建议继续补充取舍理由和可验证结果，回答会更有说服力。";
        String fallbackNext = currentRound >= totalRounds ? null : nextQuestion(currentRound + 1, resume, job);

        String projects = resume.projects().stream().limit(2)
                .map(item -> item.name() + "：" + safe(item.description()) + "；成果：" + safe(item.highlight()))
                .collect(Collectors.joining("\n"));
        AgentEvaluation ai = agentModelClient.generateJson("""
                你是严格但友善的计算机岗位面试官，负责评分和追问。
                岗位：%s
                岗位要求：%s
                候选人技能：%s
                候选项目：%s
                当前轮次：%d / %d
                候选人回答：
                <<<%s>>>

                请基于事实评分，识别 STAR 结构、技术深度、个人贡献、量化结果和表达清晰度。
                返回 JSON：
                {"score":45到100的整数,"feedback":"具体说明做得好与需改进之处，80字以内","nextQuestion":"如果还有下一轮，给出一个紧扣回答的追问；若已是最后一轮则返回空字符串"}
                不得编造候选人的经历，nextQuestion 必须基于回答或简历继续深挖。
                """.formatted(job.title(), String.join("、", job.skills()),
                String.join("、", resume.skills()), projects.isBlank() ? "暂无项目" : projects,
                currentRound, totalRounds, text), AgentEvaluation.class);

        if (ai != null && ai.score() != null) {
            int aiScore = Math.max(45, Math.min(100, ai.score()));
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

    private InterviewView toView(InterviewSessionEntity entity) {
        return new InterviewView(entity.getId(), entity.getResumeId(), entity.getJobId(), entity.getJobTitle(),
                entity.getDifficulty(), entity.getStatus(),
                entity.getCurrentRound() == null ? 0 : entity.getCurrentRound(),
                entity.getTotalRounds() == null ? 4 : entity.getTotalRounds(),
                entity.getOverallScore() == null ? 0 : entity.getOverallScore(),
                jsonCodec.readList(entity.getMessagesJson(), InterviewMessage.class),
                entity.getStartedAt(), entity.getUpdatedAt());
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
