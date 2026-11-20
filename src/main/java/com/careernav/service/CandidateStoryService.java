package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.CandidateStoryView;
import com.careernav.domain.ApiModels.InterviewMessage;
import com.careernav.domain.ApiModels.InterviewProjectEvidence;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.CandidateStoryEntity;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.CandidateStoryRepository;
import com.careernav.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 跨场次候选人故事库：保存、去重、复用和补建 STAR+R 故事。 */
@Service
public class CandidateStoryService {
    private static final int MAX_STORIES_PER_SESSION = 3;
    private static final int MAX_AGENT_STORIES = 12;

    private final CandidateStoryRepository repository;
    private final InterviewSessionRepository interviewRepository;
    private final JsonCodec jsonCodec;
    private final AgentModelClient model;

    public CandidateStoryService(CandidateStoryRepository repository,
                                 InterviewSessionRepository interviewRepository,
                                 JsonCodec jsonCodec,
                                 AgentModelClient model) {
        this.repository = repository;
        this.interviewRepository = interviewRepository;
        this.jsonCodec = jsonCodec;
        this.model = model;
    }

    @Transactional(readOnly = true)
    public List<CandidateStoryView> list(Long resumeId) {
        if (resumeId == null) return List.of();
        return repository.findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId).stream()
                .map(this::view).toList();
    }

    /** 把最近故事带入下一场面试，并记录使用次数。 */
    @Transactional
    public List<CandidateStoryView> loadForInterview(Long resumeId) {
        if (resumeId == null) return List.of();
        List<CandidateStoryEntity> stories = new ArrayList<>(
                repository.findTop12ByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId));
        if (stories.isEmpty()) return List.of();
        LocalDateTime now = LocalDateTime.now();
        for (CandidateStoryEntity story : stories) {
            story.setUsageCount((story.getUsageCount() == null ? 0 : story.getUsageCount()) + 1);
            story.setLastUsedAt(now);
        }
        repository.saveAll(stories);
        return stories.stream().limit(MAX_AGENT_STORIES).map(this::view).toList();
    }

    /** 面试完成后自动提取故事；同一场面试只处理一次。 */
    @Transactional
    public void ingestCompletedSession(InterviewSessionEntity session, ResumeView resume, JobView job,
                                       List<InterviewMessage> messages) {
        if (session == null || session.getId() == null || messages == null || messages.isEmpty()) return;
        if (repository.existsBySourceSessionIdAndDeletedAtIsNull(session.getId())) return;
        List<StoryDraft> drafts = extract(session, resume, job, messages);
        persistDrafts(session, drafts);
    }

    /** 从历史已完成面试补建故事库，用于接入前的旧数据。 */
    @Transactional
    public List<CandidateStoryView> rebuildFromHistory(Long resumeId, int limit) {
        if (resumeId == null) throw new BusinessException("请选择简历");
        int max = Math.max(1, Math.min(limit <= 0 ? 6 : limit, 20));
        int processed = 0;
        List<InterviewSessionEntity> sessions =
                interviewRepository.findByResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId, "COMPLETED");
        for (InterviewSessionEntity session : sessions) {
            if (processed >= max) break;
            if (repository.existsBySourceSessionIdAndDeletedAtIsNull(session.getId())) continue;
            List<InterviewMessage> messages = jsonCodec.readList(session.getMessagesJson(), InterviewMessage.class);
            if (messages.isEmpty()) continue;
            List<StoryDraft> drafts = extract(session, null, null, messages);
            persistDrafts(session, drafts);
            processed++;
        }
        return list(resumeId);
    }

    /** 只整理用户明确选择的已完成面试。 */
    @Transactional
    public List<CandidateStoryView> rebuildFromSessions(Long resumeId, List<Long> sessionIds) {
        if (resumeId == null) throw new BusinessException("请选择简历");
        if (sessionIds == null || sessionIds.isEmpty()) return list(resumeId);
        for (Long sessionId : new LinkedHashSet<>(sessionIds)) {
            if (sessionId == null) continue;
            InterviewSessionEntity session = interviewRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException("面试记录不存在：" + sessionId));
            if (!resumeId.equals(session.getResumeId())) {
                throw new BusinessException("面试记录不属于当前简历：" + sessionId);
            }
            if (!"COMPLETED".equals(session.getStatus())) {
                throw new BusinessException("只能从已完成面试中整理故事：" + sessionId);
            }
            if (repository.existsBySourceSessionIdAndDeletedAtIsNull(sessionId)) continue;
            List<InterviewMessage> messages = jsonCodec.readList(session.getMessagesJson(), InterviewMessage.class);
            if (messages.isEmpty()) continue;
            persistDrafts(session, extract(session, null, null, messages));
        }
        return list(resumeId);
    }
    @Transactional
    public void delete(Long id) {
        CandidateStoryEntity story = repository.findById(id)
                .orElseThrow(() -> new BusinessException("故事不存在：" + id));
        if (story.getDeletedAt() == null) {
            story.setDeletedAt(LocalDateTime.now());
            repository.save(story);
        }
    }

    @Transactional(readOnly = true)
    public List<CandidateStoryView> trash(Long resumeId) {
        if (resumeId == null) return List.of();
        return repository.findByResumeIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(resumeId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public void restore(Long id) {
        CandidateStoryEntity story = repository.findById(id)
                .orElseThrow(() -> new BusinessException("故事不存在：" + id));
        story.setDeletedAt(null);
        repository.save(story);
    }

    @Transactional
    public void permanentDelete(Long id) {
        CandidateStoryEntity story = repository.findById(id)
                .orElseThrow(() -> new BusinessException("故事不存在：" + id));
        repository.delete(story);
    }

    @Transactional
    public int clear(Long resumeId) {
        if (resumeId == null) return 0;
        List<CandidateStoryEntity> stories = repository.findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(resumeId);
        LocalDateTime now = LocalDateTime.now();
        for (CandidateStoryEntity story : stories) story.setDeletedAt(now);
        repository.saveAll(stories);
        return stories.size();
    }

    @Transactional
    public int emptyTrash(Long resumeId) {
        if (resumeId == null) return 0;
        List<CandidateStoryEntity> stories = repository.findByResumeIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(resumeId);
        repository.deleteAll(stories);
        return stories.size();
    }


    private List<StoryDraft> extract(InterviewSessionEntity session, ResumeView resume, JobView job,
                                     List<InterviewMessage> messages) {
        String transcript = messages.stream()
                .filter(message -> message != null && "user".equals(message.role()))
                .map(InterviewMessage::content)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        if (transcript.isBlank()) return List.of();
        InterviewProjectEvidence evidence = jsonCodec.read(
                session.getProjectEvidenceJson(), InterviewProjectEvidence.class);
        String prompt = """
                你是面试资料整理器。请从候选人的真实回答中提炼 1 到 3 个可复用的 STAR+R 故事。
                只使用候选人亲口说过的事实，不得编造项目、数字、职责或结果。
                如果缺少某一部分，该字段返回空字符串，不要猜测。

                目标岗位：%s
                面试类型：%s
                项目证据卡：%s
                候选人技能：%s
                候选人回答：
                <<<%s>>>

                返回 JSON：
                {"stories":[{"title":"不超过30字的故事标题","projectName":"项目名","situation":"背景/情境","task":"任务或目标","action":"候选人个人行动","result":"可验证结果","reflection":"反思或改进","competencies":["能力标签"],"evidence":"对应候选人原话的简短摘录","confidence":0到100}]}
                最多返回 %d 个故事；不要输出 Markdown。
                """.formatted(
                job == null ? safe(session.getJobTitle()) : safe(job.title()),
                safe(session.getInterviewMode()).isBlank() ? "技术面" : session.getInterviewMode(),
                evidenceText(evidence),
                resume == null ? "未提供" : String.join("、", resume.skills()),
                clip(transcript, 9000),
                MAX_STORIES_PER_SESSION);
        StoryBatch batch = model == null || !model.available() ? null : model.generateJson(prompt, StoryBatch.class);
        if (batch != null && batch.stories() != null) {
            List<StoryDraft> drafts = batch.stories().stream()
                    .filter(item -> item != null)
                    .limit(MAX_STORIES_PER_SESSION)
                    .toList();
            if (!drafts.isEmpty()) return drafts;
        }
        return List.of(fallbackDraft(evidence, session, transcript));
    }

    private StoryDraft fallbackDraft(InterviewProjectEvidence evidence, InterviewSessionEntity session, String transcript) {
        String project = evidence == null ? "" : safe(evidence.projectName());
        if (project.isBlank()) project = safe(session.getJobTitle()) + "项目";
        List<String> competencies = inferCompetencies(transcript);
        return new StoryDraft(
                clip(project + " · 面试经历", 60),
                clip(project, 80),
                "",
                "",
                clip(transcript, 4000),
                "",
                "面试中已讲述这段经历，后续应补充可验证结果和个人反思。",
                competencies,
                clip(transcript, 1200),
                45
        );
    }

    private void persistDrafts(InterviewSessionEntity session, List<StoryDraft> drafts) {
        LocalDateTime now = LocalDateTime.now();
        for (StoryDraft draft : drafts) {
            if (draft == null) continue;
            String title = first(draft.title(), "面试故事");
            String project = first(draft.projectName(), "");
            String action = clip(safe(draft.action()), 5000);
            String fingerprint = fingerprint(session.getResumeId(), project, title, action);
            Optional<CandidateStoryEntity> existing =
                    repository.findFirstByResumeIdAndFingerprintAndDeletedAtIsNull(session.getResumeId(), fingerprint);
            CandidateStoryEntity story = existing.orElseGet(CandidateStoryEntity::new);
            story.setResumeId(session.getResumeId());
            story.setJobId(session.getJobId());
            List<Long> sourceIds = sourceSessionIds(story);
            if (!sourceIds.contains(session.getId())) sourceIds.add(session.getId());
            story.setSourceSessionIdsJson(jsonCodec.write(sourceIds));
            story.setSourceSessionId(session.getId());
            story.setSourceRound(session.getCurrentRound());
            story.setInterviewMode(first(session.getInterviewMode(), "技术面"));
            story.setTitle(clip(title, 120));
            story.setProjectName(clip(project, 200));
            story.setSituation(firstNonBlank(draft.situation(), story.getSituation()));
            story.setTask(firstNonBlank(draft.task(), story.getTask()));
            story.setAction(firstNonBlank(draft.action(), story.getAction()));
            story.setResult(firstNonBlank(draft.result(), story.getResult()));
            story.setReflection(firstNonBlank(draft.reflection(), story.getReflection()));
            story.setEvidence(firstNonBlank(draft.evidence(), story.getEvidence()));
            story.setCompetenciesJson(jsonCodec.write(merge(
                    readCompetencies(story.getCompetenciesJson()), draft.competencies())));
            story.setFingerprint(fingerprint);
            int confidence = clamp(draft.confidence() == null ? 50 : draft.confidence(), 0, 100);
            story.setConfidence(story.getConfidence() == null ? confidence : Math.max(story.getConfidence(), confidence));
            if (story.getUsageCount() == null) story.setUsageCount(0);
            if (story.getCreatedAt() == null) story.setCreatedAt(now);
            story.setUpdatedAt(now);
            repository.save(story);
        }
    }

    private CandidateStoryView view(CandidateStoryEntity story) {
        return new CandidateStoryView(
                story.getId(), story.getResumeId(), story.getJobId(), story.getSourceSessionId(),
                sourceSessionIds(story), story.getSourceRound(), story.getInterviewMode(), story.getTitle(), story.getProjectName(),
                safe(story.getSituation()), safe(story.getTask()), safe(story.getAction()),
                safe(story.getResult()), safe(story.getReflection()),
                readCompetencies(story.getCompetenciesJson()), safe(story.getEvidence()),
                story.getConfidence(), story.getUsageCount() == null ? 0 : story.getUsageCount(),
                story.getLastUsedAt(), story.getCreatedAt(), story.getUpdatedAt(), story.getDeletedAt());
    }

    private List<Long> sourceSessionIds(CandidateStoryEntity story) {
        List<Long> values = new ArrayList<>(jsonCodec.readList(story.getSourceSessionIdsJson(), Long.class));
        if (values.isEmpty() && story.getSourceSessionId() != null) {
            values.add(story.getSourceSessionId());
        }
        return new ArrayList<>(values.stream().filter(value -> value != null).distinct().toList());
    }
    private List<String> readCompetencies(String json) {
        return jsonCodec.readList(json, String.class);
    }

    private List<String> merge(List<String> current, List<String> incoming) {
        LinkedHashSet<String> values = new LinkedHashSet<>(current == null ? List.of() : current);
        if (incoming != null) {
            incoming.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> clip(value.trim(), 40)).forEach(values::add);
        }
        return values.stream().limit(12).toList();
    }

    private List<String> inferCompetencies(String transcript) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (transcript.matches("(?s).*(并发|事务|幂等|缓存|索引|接口|数据库|性能).*")) values.add("技术实现");
        if (transcript.matches("(?s).*(沟通|协作|分工|推进|冲突|队友).*")) values.add("团队协作");
        if (transcript.matches("(?s).*(失败|返工|反思|改进|学习).*")) values.add("反思学习");
        if (transcript.matches("(?s).*(负责|主导|独立|我实现|我设计).*")) values.add("个人责任");
        if (values.isEmpty()) values.add("项目经历");
        return List.copyOf(values);
    }

    private String evidenceText(InterviewProjectEvidence evidence) {
        if (evidence == null) return "未提供";
        return String.join("；", List.of(
                "项目=" + safe(evidence.projectName()),
                "角色=" + safe(evidence.role()),
                "业务=" + safe(evidence.businessFlow()),
                "技术=" + safe(evidence.techStack()),
                "难点=" + safe(evidence.challenge()),
                "结果=" + safe(evidence.resultEvidence())));
    }

    static String fingerprint(Long resumeId, String projectName, String title, String action) {
        String base = safeStatic(resumeId) + "|" + normalize(projectName) + "|" + normalize(title)
                + "|" + normalize(clipStatic(action, 180));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            return Integer.toHexString(base.hashCode());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：！？、（）【】《》]+", "");
    }

    private static String safeStatic(Long value) { return value == null ? "" : String.valueOf(value); }
    private static String clipStatic(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private String firstNonBlank(String incoming, String current) {
        return incoming == null || incoming.isBlank() ? safe(current) : clip(incoming.trim(), 5000);
    }
    private String first(String value, String fallback) {
        String text = safe(value).trim();
        return text.isBlank() ? safe(fallback) : text;
    }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private String safe(String value) { return value == null ? "" : value; }
    private String clip(String value, int max) {
        String text = safe(value).trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record StoryBatch(List<StoryDraft> stories) {}
    public record StoryDraft(String title, String projectName, String situation, String task,
                             String action, String result, String reflection,
                             List<String> competencies, String evidence, Integer confidence) {}
}
