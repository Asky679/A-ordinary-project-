package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Java 技能水平验证：保留单轮兼容，并提供多轮自适应验证。 */
@Service
public class SkillAssessmentService {
    private static final String JAVA = "Java";
    private static final int TEST_MINUTES = 15;
    private static final int MULTI_ROUND_MINUTES = 10;
    private static final int FOLLOW_UP_ROUND_MINUTES = 8;
    private static final int ROUND_ONE_SIZE = 6;
    private static final int FOLLOW_UP_ROUND_SIZE = 4;
    private static final int MULTI_ROUND_MAX = 3;
    private static final double GUESS_RISK_REVIEW_THRESHOLD = 0.35;
    private static final double CONSISTENCY_REVIEW_THRESHOLD = 0.65;
    // BKT 默认参数：题库规模较小时使用固定先验，后续用历史答题数据校准。
    private static final double BKT_P_INIT = 0.30;
    private static final double BKT_P_TRANSITION = 0.15;
    private static final double BKT_P_SLIP = 0.10;
    private static final double BKT_P_GUESS = 0.20;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ResumeService resumeService;

    public SkillAssessmentService(JdbcTemplate jdbc, ObjectMapper objectMapper, ResumeService resumeService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.resumeService = resumeService;
    }

    @Transactional
    public SkillTestView startJavaTest(Long resumeId) {
        if (resumeId == null) throw new BusinessException("请选择简历");
        resumeService.get(resumeId);
        List<QuestionRow> selected = new ArrayList<>();
        selected.addAll(sample("BASIC", 4));
        selected.addAll(sample("INTERMEDIATE", 4));
        selected.addAll(sample("ADVANCED", 2));
        if (selected.size() < 5) throw new BusinessException("Java 题库数量不足，请先初始化题库");
        Collections.shuffle(selected, new Random());

        LocalDateTime started = LocalDateTime.now();
        LocalDateTime expires = started.plusMinutes(TEST_MINUTES);
        String idsJson;
        try {
            idsJson = objectMapper.writeValueAsString(selected.stream().map(QuestionRow::id).toList());
        } catch (Exception e) {
            throw new BusinessException("测试题目序列化失败：" + e.getMessage());
        }
        Long sessionId = jdbc.queryForObject("""
                INSERT INTO skill_assessment_sessions
                    (resume_id, skill, status, question_ids_json, started_at, expires_at)
                VALUES (?, ?, 'IN_PROGRESS', ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class, resumeId, JAVA, idsJson, started, expires);
        if (sessionId == null) throw new BusinessException("技能测试创建失败");
        List<SkillTestQuestion> questions = selected.stream()
                .map(q -> new SkillTestQuestion(q.id(), q.level(), q.topic(), q.questionText(), q.options()))
                .toList();
        return new SkillTestView(sessionId, JAVA, "IN_PROGRESS", started, expires, questions);
    }

    /**
     * 多轮验证：第一轮广度诊断，第二轮针对低掌握点复测，必要时第三轮做变式确认。
     * 原有单轮 startJavaTest/submit 接口保留，供兼容调用。
     */
    @Transactional
    public SkillRoundView startJavaMultiRound(Long resumeId) {
        if (resumeId == null) throw new BusinessException("请选择简历");
        resumeService.get(resumeId);
        LocalDateTime started = LocalDateTime.now();
        LocalDateTime expires = started.plusMinutes(MULTI_ROUND_MINUTES);
        Long sessionId = jdbc.queryForObject("""
                INSERT INTO skill_assessment_sessions
                    (resume_id, skill, status, question_ids_json, started_at, expires_at, current_round, total_rounds)
                VALUES (?, ?, 'IN_PROGRESS', '[]'::jsonb, ?, ?, 1, ?)
                RETURNING id
                """, Long.class, resumeId, JAVA, started, expires, MULTI_ROUND_MAX);
        if (sessionId == null) throw new BusinessException("多轮测试创建失败");
        List<QuestionRow> questions = planFirstRound();
        if (questions.size() < ROUND_ONE_SIZE) {
            throw new BusinessException("题库数量不足，请先扩充 Java 题库");
        }
        saveRound(sessionId, 1, questions, List.of(), started, expires);
        updateSessionRound(sessionId, questions, 1, MULTI_ROUND_MAX, expires);
        return roundView(sessionId, 1, MULTI_ROUND_MAX, "IN_PROGRESS", started, expires, List.of(), questions);
    }

    @Transactional
    public SkillRoundResult submitJavaRound(Long sessionId, int roundNo, SkillRoundSubmitRequest request) {
        if (sessionId == null) throw new BusinessException("测试会话不存在");
        Map<String, Object> session;
        try {
            session = jdbc.queryForMap("SELECT * FROM skill_assessment_sessions WHERE id = ?", sessionId);
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException("测试会话不存在：" + sessionId);
        }
        if (!"IN_PROGRESS".equals(String.valueOf(session.get("status")))) {
            throw new BusinessException("本次测试已经结束，不能重复提交");
        }
        int currentRound = ((Number) session.get("current_round")).intValue();
        if (roundNo != currentRound) {
            throw new BusinessException("当前应提交第 " + currentRound + " 轮，而不是第 " + roundNo + " 轮");
        }
        Map<String, Object> round = loadRound(sessionId, roundNo);
        List<Long> ids = readIds(String.valueOf(round.get("question_ids_json")));
        List<QuestionRow> questions = ids.stream().map(this::question).filter(Objects::nonNull).toList();
        if (questions.isEmpty()) throw new BusinessException("测试会话不存在");
        Map<Long, SkillRoundAnswer> answers = answerMap(request);
        LocalDateTime sessionStarted = timestamp(session.get("started_at"));
        LocalDateTime roundExpires = timestamp(round.get("expires_at"));
        LocalDateTime submitted = LocalDateTime.now();
        boolean expired = submitted.isAfter(roundExpires);
        long resumeId = ((Number) session.get("resume_id")).longValue();

        RoundEvaluation evaluation = evaluateRound(resumeId, roundNo, questions, answers);
        saveAnswerEvaluations(sessionId, evaluation.answers());
        RoundMetrics roundMetrics = metrics(evaluation.answers(), evaluation.mastery(), roundNo, expired);
        updateRoundSummary(sessionId, roundNo, roundMetrics, submitted, expired);
        if (!expired) updateMasteryState(resumeId, sessionId, evaluation.mastery(), submitted);

        List<AnswerEvaluation> allAnswers = loadAnswerEvaluations(sessionId);
        Map<String, MasteryState> mastery = loadMasteryState(resumeId);
        RoundMetrics cumulativeMetrics = metrics(allAnswers, mastery, roundNo, expired);
        SkillTestResult roundResult = buildResult(sessionId, roundMetrics, sessionStarted, submitted, expired, false);
        boolean needsNext = !expired && roundNo < MULTI_ROUND_MAX
                && (roundNo == 1 || cumulativeMetrics.guessRisk >= GUESS_RISK_REVIEW_THRESHOLD
                || cumulativeMetrics.consistency < CONSISTENCY_REVIEW_THRESHOLD);

        if (needsNext) {
            List<QuestionRow> nextQuestions = planTargetedRound(resumeId, sessionId, roundNo + 1);
            if (!nextQuestions.isEmpty()) {
                LocalDateTime nextStarted = LocalDateTime.now();
                LocalDateTime nextExpires = nextStarted.plusMinutes(FOLLOW_UP_ROUND_MINUTES);
                List<String> targetTopics = nextQuestions.stream().map(QuestionRow::topic).filter(Objects::nonNull).distinct().toList();
                saveRound(sessionId, roundNo + 1, nextQuestions, targetTopics, nextStarted, nextExpires);
                updateSessionRound(sessionId, nextQuestions, roundNo + 1, MULTI_ROUND_MAX, nextExpires);
                SkillRoundView next = roundView(sessionId, roundNo + 1, MULTI_ROUND_MAX,
                        "IN_PROGRESS", nextStarted, nextExpires, targetTopics, nextQuestions);
                return new SkillRoundResult(sessionId, roundNo, MULTI_ROUND_MAX, false,
                        roundResult, next, cumulativeMetrics.guessRisk, cumulativeMetrics.consistency);
            }
        }

        boolean saved = !expired && !allAnswers.isEmpty();
        SkillTestResult finalResult = buildResult(sessionId, cumulativeMetrics, sessionStarted, submitted, expired, saved);
        if (saved) updateProficiency(resumeId, sessionId, finalResult, cumulativeMetrics, roundNo);
        updateSessionFinal(sessionId, finalResult, cumulativeMetrics, submitted, expired, roundNo, saved);
        return new SkillRoundResult(sessionId, roundNo, roundNo, true, finalResult, null,
                cumulativeMetrics.guessRisk, cumulativeMetrics.consistency);
    }

    private List<QuestionRow> planFirstRound() {
        List<QuestionRow> selected = new ArrayList<>();
        addUnique(selected, sample("BASIC", 2));
        addUnique(selected, sample("INTERMEDIATE", 2));
        addUnique(selected, sample("ADVANCED", 2));
        Collections.shuffle(selected, new Random());
        return selected;
    }

    private List<QuestionRow> planTargetedRound(long resumeId, long sessionId, int roundNo) {
        Set<Long> used = usedQuestionIds(sessionId);
        List<AnswerEvaluation> history = loadAnswerEvaluations(sessionId);
        Map<String, MasteryState> mastery = loadMasteryState(resumeId);
        List<String> focusTopics = focusTopics(history, mastery, roundNo);
        List<QuestionRow> pool = new ArrayList<>(questionPool());
        pool.removeIf(q -> used.contains(q.id()));
        Collections.shuffle(pool, new Random());
        List<QuestionRow> selected = new ArrayList<>();
        for (String topic : focusTopics) {
            for (QuestionRow q : new ArrayList<>(pool)) {
                if (topic.equals(q.topic())) {
                    selected.add(q);
                    pool.remove(q);
                    break;
                }
            }
            if (selected.size() >= FOLLOW_UP_ROUND_SIZE) break;
        }
        while (selected.size() < FOLLOW_UP_ROUND_SIZE && !pool.isEmpty()) selected.add(pool.remove(0));
        return selected;
    }

    private List<String> focusTopics(List<AnswerEvaluation> history, Map<String, MasteryState> mastery, int roundNo) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        if (roundNo >= 3) {
            Map<String, int[]> stats = topicStats(history);
            stats.entrySet().stream()
                    .filter(e -> e.getValue()[1] >= 2 && e.getValue()[0] > 0 && e.getValue()[0] < e.getValue()[1])
                    .map(Map.Entry::getKey).forEach(topics::add);
        }
        history.stream().filter(a -> !a.correct()).map(AnswerEvaluation::topic)
                .filter(t -> t != null && !t.isBlank()).forEach(topics::add);
        mastery.entrySet().stream().sorted(Comparator.comparingDouble(e -> e.getValue().probability()))
                .map(Map.Entry::getKey).forEach(topics::add);
        return topics.stream().limit(4).toList();
    }

    private Map<Long, SkillRoundAnswer> answerMap(SkillRoundSubmitRequest request) {
        Map<Long, SkillRoundAnswer> answers = new HashMap<>();
        if (request != null && request.answers() != null) {
            for (SkillRoundAnswer answer : request.answers()) {
                if (answer != null && answer.questionId() != null) answers.put(answer.questionId(), answer);
            }
        }
        return answers;
    }

    private RoundEvaluation evaluateRound(long resumeId, int roundNo, List<QuestionRow> questions,
                                          Map<Long, SkillRoundAnswer> answers) {
        Map<String, MasteryState> mastery = loadMasteryState(resumeId);
        List<AnswerEvaluation> evaluated = new ArrayList<>();
        for (QuestionRow q : questions) {
            SkillRoundAnswer answer = answers.get(q.id());
            String selected = answer == null || answer.option() == null ? "" : answer.option().trim().toUpperCase(Locale.ROOT);
            boolean correct = selected.equalsIgnoreCase(q.correctOption());
            String topic = q.topic() == null || q.topic().isBlank() ? "未分类" : q.topic().trim();
            double weight = weight(q.level());
            MasteryState prior = mastery.getOrDefault(topic, new MasteryState(topic, BKT_P_INIT, 0, 0));
            double before = prior.probability();
            double after = bktUpdate(before, correct);
            mastery.put(topic, new MasteryState(topic, after, prior.attempts() + 1,
                    prior.correctCount() + (correct ? 1 : 0)));
            Integer responseMs = answer == null ? null : answer.responseMs();
            Double confidence = answer == null || answer.selfConfidence() == null ? null
                    : Math.max(0.0, Math.min(1.0, answer.selfConfidence()));
            double itemRisk = answerGuessRisk(correct, q.level(), responseMs, confidence, before);
            evaluated.add(new AnswerEvaluation(roundNo, q.id(), q.level(), topic, q.questionText(),
                    selected, q.correctOption(), correct, weight, responseMs, confidence,
                    before, after, itemRisk, q.explanation()));
        }
        return new RoundEvaluation(evaluated, mastery);
    }

    private double answerGuessRisk(boolean correct, String level, Integer responseMs,
                                   Double confidence, double prior) {
        if (!correct) return 0.0;
        double risk = 0.0;
        if (prior < 0.45) risk += 0.25;
        int threshold = switch (level) {
            case "BASIC" -> 6000;
            case "INTERMEDIATE" -> 10000;
            case "ADVANCED" -> 15000;
            default -> 10000;
        };
        if (responseMs != null && responseMs > 0 && responseMs < threshold) risk += 0.35;
        if (confidence != null && confidence < 0.5) risk += 0.30;
        return round4(Math.min(1.0, risk));
    }

    private RoundMetrics metrics(List<AnswerEvaluation> answers, Map<String, MasteryState> mastery,
                                 int rounds, boolean expired) {
        Map<String, int[]> levelStats = new LinkedHashMap<>();
        levelStats.put("BASIC", new int[2]);
        levelStats.put("INTERMEDIATE", new int[2]);
        levelStats.put("ADVANCED", new int[2]);
        Map<String, Double> topicWeights = new LinkedHashMap<>();
        double earned = 0, totalWeight = 0;
        int correctCount = 0;
        List<String> weakTopics = new ArrayList<>();
        for (AnswerEvaluation answer : answers) {
            levelStats.computeIfAbsent(answer.level(), key -> new int[2])[1]++;
            totalWeight += answer.weight();
            if (answer.correct()) {
                earned += answer.weight();
                correctCount++;
                levelStats.get(answer.level())[0]++;
            } else if (!weakTopics.contains(answer.topic())) {
                weakTopics.add(answer.topic());
            }
            topicWeights.merge(answer.topic(), answer.weight(), Double::sum);
        }
        int totalCount = answers.size();
        int score = totalWeight <= 0 ? 0 : (int) Math.round(earned / totalWeight * 100);
        double consistency = topicConsistency(answers);
        double baseRisk = answers.stream().mapToDouble(AnswerEvaluation::guessRisk).average().orElse(0.0);
        double guessRisk = round4(Math.min(1.0, baseRisk * 0.7 + (1.0 - consistency) * 0.3));
        return new RoundMetrics(score, classify(levelStats), correctCount, totalCount, levelStats,
                weakTopics.stream().limit(5).toList(), answers.stream().map(AnswerEvaluation::review).toList(),
                weightedMastery(mastery, topicWeights),
                bktConfidence(totalCount, topicWeights.size(), rounds, expired),
                guessRisk, consistency, topicWeights, mastery);
    }

    private double topicConsistency(List<AnswerEvaluation> answers) {
        Map<String, int[]> stats = topicStats(answers);
        double sum = 0;
        int count = 0;
        for (int[] stat : stats.values()) {
            if (stat[1] < 2) continue;
            sum += Math.max(stat[0], stat[1] - stat[0]) / (double) stat[1];
            count++;
        }
        return count == 0 ? 0.5 : round4(sum / count);
    }

    private Map<String, int[]> topicStats(List<AnswerEvaluation> answers) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (AnswerEvaluation answer : answers) {
            int[] stat = stats.computeIfAbsent(answer.topic(), key -> new int[2]);
            stat[1]++;
            if (answer.correct()) stat[0]++;
        }
        return stats;
    }

    private SkillTestResult buildResult(long sessionId, RoundMetrics metrics, LocalDateTime startedAt,
                                        LocalDateTime submittedAt, boolean expired, boolean saved) {
        List<SkillMasteryView> bktBreakdown = metrics.topicWeights().keySet().stream()
                .map(topic -> {
                    MasteryState state = metrics.mastery().getOrDefault(topic,
                            new MasteryState(topic, BKT_P_INIT, 0, 0));
                    return new SkillMasteryView(topic, round4(state.probability()), state.attempts(), state.correctCount());
                })
                .sorted(Comparator.comparingDouble(SkillMasteryView::masteryProbability))
                .toList();
        List<SkillLevelBreakdown> breakdown = metrics.levelStats().entrySet().stream()
                .map(e -> new SkillLevelBreakdown(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[1] == 0 ? 0.0 : Math.round(e.getValue()[0] * 1000.0 / e.getValue()[1]) / 10.0))
                .toList();
        return new SkillTestResult(sessionId, JAVA, metrics.score(), metrics.level(),
                confidence(metrics.totalCount(), expired), metrics.correctCount(), metrics.totalCount(), saved,
                breakdown, metrics.weakTopics(), metrics.reviews(),
                metrics.bktMastery(), metrics.bktConfidence(), bktBreakdown, startedAt, submittedAt);
    }

    private void saveAnswerEvaluations(long sessionId, List<AnswerEvaluation> answers) {
        for (AnswerEvaluation answer : answers) {
            jdbc.update("""
                    INSERT INTO skill_assessment_answers
                        (session_id, round_no, question_id, selected_option, correct, question_level, weight,
                         response_ms, self_confidence, bkt_before, bkt_after, guess_risk, answered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (session_id, question_id) DO UPDATE SET
                        selected_option=EXCLUDED.selected_option, correct=EXCLUDED.correct,
                        response_ms=EXCLUDED.response_ms, self_confidence=EXCLUDED.self_confidence,
                        bkt_before=EXCLUDED.bkt_before, bkt_after=EXCLUDED.bkt_after,
                        guess_risk=EXCLUDED.guess_risk, answered_at=EXCLUDED.answered_at
                    """, sessionId, answer.roundNo(), answer.questionId(), answer.selected(),
                    answer.correct(), answer.level(), answer.weight(), answer.responseMs(),
                    answer.selfConfidence(), answer.bktBefore(), answer.bktAfter(), answer.guessRisk(),
                    LocalDateTime.now());
        }
    }

    private void updateMasteryState(long resumeId, long sessionId, Map<String, MasteryState> mastery,
                                    LocalDateTime updatedAt) {
        for (MasteryState state : mastery.values()) {
            jdbc.update("""
                    INSERT INTO skill_mastery_state
                        (resume_id, skill, knowledge_component, mastery_probability, attempts, correct_count,
                         last_session_id, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (resume_id, skill, knowledge_component) DO UPDATE SET
                        mastery_probability=EXCLUDED.mastery_probability, attempts=EXCLUDED.attempts,
                        correct_count=EXCLUDED.correct_count, last_session_id=EXCLUDED.last_session_id,
                        updated_at=EXCLUDED.updated_at
                    """, resumeId, JAVA, state.knowledgeComponent(), state.probability(), state.attempts(),
                    state.correctCount(), sessionId, updatedAt);
        }
    }

    private void updateRoundSummary(long sessionId, int roundNo, RoundMetrics metrics,
                                    LocalDateTime submittedAt, boolean expired) {
        jdbc.update("""
                UPDATE skill_assessment_rounds
                SET status=?, submitted_at=?, score=?, correct_count=?, total_count=?,
                    bkt_mastery=?, bkt_confidence=?, guess_risk=?, consistency=?
                WHERE session_id=? AND round_no=?
                """, expired ? "EXPIRED" : "COMPLETED", submittedAt, metrics.score(),
                metrics.correctCount(), metrics.totalCount(), metrics.bktMastery(),
                metrics.bktConfidence(), metrics.guessRisk(), metrics.consistency(), sessionId, roundNo);
    }

    private void saveRound(long sessionId, int roundNo, List<QuestionRow> questions, List<String> targetTopics,
                           LocalDateTime startedAt, LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO skill_assessment_rounds
                    (session_id, round_no, status, question_ids_json, target_topics_json, started_at, expires_at)
                VALUES (?, ?, 'IN_PROGRESS', ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (session_id, round_no) DO UPDATE SET
                    status='IN_PROGRESS', question_ids_json=EXCLUDED.question_ids_json,
                    target_topics_json=EXCLUDED.target_topics_json, started_at=EXCLUDED.started_at,
                    expires_at=EXCLUDED.expires_at, submitted_at=NULL
                """, sessionId, roundNo, jsonIds(questions), jsonText(targetTopics), startedAt, expiresAt);
    }

    private void updateSessionRound(long sessionId, List<QuestionRow> questions, int currentRound,
                                    int totalRounds, LocalDateTime expiresAt) {
        jdbc.update("""
                UPDATE skill_assessment_sessions
                SET question_ids_json=?::jsonb, current_round=?, total_rounds=?, expires_at=?,
                    status='IN_PROGRESS'
                WHERE id=?
                """, jsonIds(questions), currentRound, totalRounds, expiresAt, sessionId);
    }

    private void updateSessionFinal(long sessionId, SkillTestResult result, RoundMetrics metrics,
                                    LocalDateTime submittedAt, boolean expired, int roundNo, boolean saved) {
        int duration = (int) Math.max(0, Duration.between(result.startedAt(), submittedAt).getSeconds());
        jdbc.update("""
                UPDATE skill_assessment_sessions
                SET status=?, submitted_at=?, duration_seconds=?, score=?, level=?, confidence=?,
                    correct_count=?, total_count=?, bkt_mastery=?, bkt_confidence=?,
                    current_round=?, total_rounds=?, guess_risk=?, consistency=?
                WHERE id=?
                """, expired ? "EXPIRED" : "COMPLETED", submittedAt, duration, result.score(), result.level(),
                result.confidence(), result.correctCount(), result.totalCount(), result.bktMastery(),
                result.bktConfidence(), roundNo, roundNo, metrics.guessRisk(), metrics.consistency(), sessionId);
    }

    private void updateProficiency(long resumeId, long sessionId, SkillTestResult result,
                                   RoundMetrics metrics, int rounds) {
        jdbc.update("""
                INSERT INTO skill_proficiency
                    (resume_id, skill, declared_level, verified_level, score, confidence, evidence_type,
                     session_id, bkt_mastery, bkt_confidence, guess_risk, consistency, rounds_completed, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'MULTI_ROUND_TEST', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (resume_id, skill) DO UPDATE SET
                    verified_level=EXCLUDED.verified_level, score=EXCLUDED.score,
                    confidence=EXCLUDED.confidence, evidence_type=EXCLUDED.evidence_type,
                    session_id=EXCLUDED.session_id, bkt_mastery=EXCLUDED.bkt_mastery,
                    bkt_confidence=EXCLUDED.bkt_confidence, guess_risk=EXCLUDED.guess_risk,
                    consistency=EXCLUDED.consistency, rounds_completed=EXCLUDED.rounds_completed,
                    updated_at=EXCLUDED.updated_at
                """, resumeId, JAVA, result.level(), result.level(), result.score(), result.confidence(),
                sessionId, result.bktMastery(), result.bktConfidence(), metrics.guessRisk(),
                metrics.consistency(), rounds, LocalDateTime.now());
    }

    private SkillRoundView roundView(long sessionId, int roundNo, int totalRounds, String status,
                                     LocalDateTime startedAt, LocalDateTime expiresAt,
                                     List<String> targetTopics, List<QuestionRow> questions) {
        List<SkillTestQuestion> views = questions.stream()
                .map(q -> new SkillTestQuestion(q.id(), q.level(), q.topic(), q.questionText(), q.options()))
                .toList();
        return new SkillRoundView(sessionId, JAVA, roundNo, totalRounds, status, startedAt, expiresAt,
                targetTopics, views);
    }

    private Map<String, Object> loadRound(long sessionId, int roundNo) {
        return jdbc.queryForMap("SELECT * FROM skill_assessment_rounds WHERE session_id=? AND round_no=?",
                sessionId, roundNo);
    }

    private List<AnswerEvaluation> loadAnswerEvaluations(long sessionId) {
        return jdbc.query("""
                SELECT a.round_no, a.question_id, a.selected_option, a.correct, a.question_level, a.weight,
                       a.response_ms, a.self_confidence, a.bkt_before, a.bkt_after, a.guess_risk,
                       q.topic, q.question_text, q.correct_option, q.explanation
                FROM skill_assessment_answers a
                JOIN skill_questions q ON q.id = a.question_id
                WHERE a.session_id=? ORDER BY a.id
                """, (rs, rowNum) -> new AnswerEvaluation(
                rs.getInt("round_no"), rs.getLong("question_id"), rs.getString("question_level"),
                rs.getString("topic"), rs.getString("question_text"), rs.getString("selected_option"),
                rs.getString("correct_option"), rs.getBoolean("correct"), rs.getDouble("weight"),
                (Integer) rs.getObject("response_ms"),
                rs.getBigDecimal("self_confidence") == null ? null : rs.getBigDecimal("self_confidence").doubleValue(),
                rs.getBigDecimal("bkt_before") == null ? BKT_P_INIT : rs.getBigDecimal("bkt_before").doubleValue(),
                rs.getBigDecimal("bkt_after") == null ? BKT_P_INIT : rs.getBigDecimal("bkt_after").doubleValue(),
                rs.getBigDecimal("guess_risk") == null ? 0.0 : rs.getBigDecimal("guess_risk").doubleValue(),
                rs.getString("explanation")), sessionId);
    }

    private Set<Long> usedQuestionIds(long sessionId) {
        Set<Long> used = new LinkedHashSet<>();
        jdbc.query("SELECT question_ids_json::text FROM skill_assessment_rounds WHERE session_id=?",
                rs -> { used.addAll(readIds(rs.getString(1))); }, sessionId);
        return used;
    }

    private List<QuestionRow> questionPool() {
        return jdbc.query("""
                SELECT id, level, topic, question_text, options_json, correct_option, explanation
                FROM skill_questions WHERE skill=? AND active=TRUE AND verified=TRUE
                """, (rs, rowNum) -> row(rs.getLong("id"), rs.getString("level"), rs.getString("topic"),
                rs.getString("question_text"), rs.getString("options_json"), rs.getString("correct_option"),
                rs.getString("explanation")), JAVA);
    }

    private void addUnique(List<QuestionRow> target, List<QuestionRow> rows) {
        Set<Long> ids = target.stream().map(QuestionRow::id).collect(Collectors.toSet());
        rows.stream().filter(q -> ids.add(q.id())).forEach(target::add);
    }

    private String jsonIds(List<QuestionRow> questions) {
        try {
            return objectMapper.writeValueAsString(questions.stream().map(QuestionRow::id).toList());
        } catch (Exception e) {
            throw new BusinessException("题目序列化失败：" + e.getMessage());
        }
    }

    private String jsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("测试轮次序列化失败：" + e.getMessage());
        }
    }

    private double bktConfidence(int questions, int topics, int rounds, boolean expired) {
        double value = Math.min(0.92, 0.35 + questions * 0.025 + topics * 0.01 + rounds * 0.05);
        if (expired) value = Math.max(0.25, value - 0.15);
        return Math.round(value * 100) / 100.0;
    }

    private record RoundEvaluation(List<AnswerEvaluation> answers, Map<String, MasteryState> mastery) {}

    private record RoundMetrics(int score, String level, int correctCount, int totalCount,
                                Map<String, int[]> levelStats, List<String> weakTopics,
                                List<SkillQuestionFeedback> reviews, double bktMastery,
                                double bktConfidence, double guessRisk, double consistency,
                                Map<String, Double> topicWeights, Map<String, MasteryState> mastery) {}

    private record AnswerEvaluation(int roundNo, Long questionId, String level, String topic,
                                    String questionText, String selected, String correctOption,
                                    boolean correct, double weight, Integer responseMs, Double selfConfidence,
                                    double bktBefore, double bktAfter, double guessRisk, String explanation) {
        SkillQuestionFeedback review() {
            return new SkillQuestionFeedback(questionId, level, topic, questionText, selected, correctOption,
                    correct, explanation);
        }
    }

    @Transactional
    public SkillTestResult submit(Long sessionId, SkillTestSubmitRequest request) {
        if (sessionId == null) throw new BusinessException("测试会话不存在");
        Map<String, Object> session;
        try {
            session = jdbc.queryForMap("SELECT * FROM skill_assessment_sessions WHERE id = ?", sessionId);
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException("测试会话不存在：" + sessionId);
        }
        if (!"IN_PROGRESS".equals(String.valueOf(session.get("status")))) {
            throw new BusinessException("本次测试已经提交，不能重复提交");
        }
        List<Long> ids = readIds(String.valueOf(session.get("question_ids_json")));
        Map<Long, String> answers = new HashMap<>();
        if (request != null && request.answers() != null) {
            for (SkillAnswer answer : request.answers()) {
                if (answer != null && answer.questionId() != null) {
                    answers.put(answer.questionId(), answer.option() == null ? "" : answer.option().trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        LocalDateTime started = timestamp(session.get("started_at"));
        LocalDateTime expires = timestamp(session.get("expires_at"));
        LocalDateTime submitted = LocalDateTime.now();
        boolean expired = submitted.isAfter(expires);

        Map<Long, QuestionRow> questions = new LinkedHashMap<>();
        for (Long id : ids) {
            QuestionRow row = question(id);
            if (row != null) questions.put(id, row);
        }

        int correctCount = 0;
        double earned = 0, totalWeight = 0;
        Map<String, int[]> levelStat = new LinkedHashMap<>();
        levelStat.put("BASIC", new int[2]);
        levelStat.put("INTERMEDIATE", new int[2]);
        levelStat.put("ADVANCED", new int[2]);
        List<SkillQuestionFeedback> reviews = new ArrayList<>();
        List<String> weakTopics = new ArrayList<>();
        long resumeId = ((Number) session.get("resume_id")).longValue();
        Map<String, MasteryState> mastery = loadMasteryState(resumeId);
        Map<String, Double> topicWeights = new LinkedHashMap<>();

        for (QuestionRow q : questions.values()) {
            String selected = answers.getOrDefault(q.id(), "");
            boolean correct = selected.equalsIgnoreCase(q.correctOption());
            String component = q.topic() == null || q.topic().isBlank() ? "\u672a\u5206\u7c7b" : q.topic().trim();
            double weight = weight(q.level());
            totalWeight += weight;
            int[] stat = levelStat.computeIfAbsent(q.level(), key -> new int[2]);
            stat[1]++;
            if (correct) {
                earned += weight;
                correctCount++;
                stat[0]++;
            } else if (!weakTopics.contains(component)) {
                weakTopics.add(component);
            }

            // Update BKT mastery after each question; repeated topics accumulate.
            MasteryState prior = mastery.getOrDefault(component,
                    new MasteryState(component, BKT_P_INIT, 0, 0));
            mastery.put(component, new MasteryState(component, bktUpdate(prior.probability(), correct),
                    prior.attempts() + 1, prior.correctCount() + (correct ? 1 : 0)));
            topicWeights.merge(component, weight, Double::sum);

            reviews.add(new SkillQuestionFeedback(q.id(), q.level(), q.topic(), q.questionText(),
                    selected, q.correctOption(), correct, q.explanation()));
            jdbc.update("""
                    INSERT INTO skill_assessment_answers
                        (session_id, question_id, selected_option, correct, question_level, weight, answered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (session_id, question_id) DO NOTHING
                    """, sessionId, q.id(), selected, correct, q.level(), weight, submitted);
        }

        int score = totalWeight <= 0 ? 0 : (int) Math.round(earned / totalWeight * 100);
        String level = classify(levelStat);
        double confidence = confidence(questions.size(), expired);
        String status = expired ? "EXPIRED" : "COMPLETED";
        int duration = (int) Math.max(0, Duration.between(started, submitted).getSeconds());
        double bktMastery = weightedMastery(mastery, topicWeights);
        double bktConfidence = bktConfidence(questions.size(), topicWeights.size(), expired);
        List<SkillMasteryView> bktBreakdown = topicWeights.keySet().stream()
                .map(key -> {
                    MasteryState state = mastery.get(key);
                    return new SkillMasteryView(key, round4(state.probability()), state.attempts(), state.correctCount());
                })
                .sorted(Comparator.comparingDouble(SkillMasteryView::masteryProbability))
                .toList();

        jdbc.update("""
                UPDATE skill_assessment_sessions
                SET status=?, submitted_at=?, duration_seconds=?, score=?, level=?, confidence=?,
                    correct_count=?, total_count=?, bkt_mastery=?, bkt_confidence=?
                WHERE id=?
                """, status, submitted, duration, score, level, confidence, correctCount, questions.size(),
                bktMastery, bktConfidence, sessionId);

        boolean saved = !expired && !questions.isEmpty();
        if (saved) {
            for (String component : topicWeights.keySet()) {
                MasteryState state = mastery.get(component);
                jdbc.update("""
                        INSERT INTO skill_mastery_state
                            (resume_id, skill, knowledge_component, mastery_probability, attempts,
                             correct_count, last_session_id, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (resume_id, skill, knowledge_component) DO UPDATE SET
                            mastery_probability=EXCLUDED.mastery_probability,
                            attempts=EXCLUDED.attempts, correct_count=EXCLUDED.correct_count,
                            last_session_id=EXCLUDED.last_session_id, updated_at=EXCLUDED.updated_at
                        """, resumeId, JAVA, component, state.probability(), state.attempts(), state.correctCount(),
                        sessionId, submitted);
            }
            jdbc.update("""
                    INSERT INTO skill_proficiency
                        (resume_id, skill, declared_level, verified_level, score, confidence, evidence_type,
                         session_id, bkt_mastery, bkt_confidence, updated_at)
                    VALUES (?, ?, '自述', ?, ?, ?, 'SKILL_TEST', ?, ?, ?, ?)
                    ON CONFLICT (resume_id, skill) DO UPDATE SET
                        verified_level=EXCLUDED.verified_level, score=EXCLUDED.score,
                        confidence=EXCLUDED.confidence, evidence_type=EXCLUDED.evidence_type,
                        session_id=EXCLUDED.session_id, bkt_mastery=EXCLUDED.bkt_mastery,
                        bkt_confidence=EXCLUDED.bkt_confidence, updated_at=EXCLUDED.updated_at
                    """, resumeId, JAVA, level, score, confidence, sessionId,
                    bktMastery, bktConfidence, submitted);
        }

        List<SkillLevelBreakdown> breakdown = levelStat.entrySet().stream()
                .map(e -> new SkillLevelBreakdown(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[1] == 0 ? 0.0 : Math.round(e.getValue()[0] * 1000.0 / e.getValue()[1]) / 10.0))
                .toList();
        return new SkillTestResult(sessionId, JAVA, score, level, confidence, correctCount, questions.size(),
                saved, breakdown, weakTopics.stream().limit(4).toList(), reviews,
                bktMastery, bktConfidence, bktBreakdown, started, submitted);
    }

    @Transactional(readOnly = true)
    public SkillProficiencyView proficiency(Long resumeId, String skill) {
        resumeService.get(resumeId);
        try {
            return jdbc.queryForObject("""
                    SELECT resume_id, skill, declared_level, verified_level, score, confidence, evidence_type,
                           bkt_mastery, bkt_confidence, guess_risk, consistency, rounds_completed, updated_at
                    FROM skill_proficiency WHERE resume_id=? AND skill=?
                    """, (rs, rowNum) -> new SkillProficiencyView(
                    rs.getLong("resume_id"), rs.getString("skill"), rs.getString("declared_level"),
                    rs.getString("verified_level"), (Integer) rs.getObject("score"),
                    rs.getBigDecimal("confidence") == null ? null : rs.getBigDecimal("confidence").doubleValue(),
                    rs.getString("evidence_type"),
                    rs.getBigDecimal("bkt_mastery") == null ? null : rs.getBigDecimal("bkt_mastery").doubleValue(),
                    rs.getBigDecimal("bkt_confidence") == null ? null : rs.getBigDecimal("bkt_confidence").doubleValue(),
                    rs.getBigDecimal("guess_risk") == null ? null : rs.getBigDecimal("guess_risk").doubleValue(),
                    rs.getBigDecimal("consistency") == null ? null : rs.getBigDecimal("consistency").doubleValue(),
                    (Integer) rs.getObject("rounds_completed"),
                    rs.getTimestamp("updated_at").toLocalDateTime()),
                    resumeId, skill);
        } catch (EmptyResultDataAccessException e) {
            return new SkillProficiencyView(resumeId, skill, "自述", null, null, null,
                    "SELF_DECLARED", null, null, null);
        }
    }

    private Map<String, MasteryState> loadMasteryState(long resumeId) {
        Map<String, MasteryState> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT knowledge_component, mastery_probability, attempts, correct_count
                FROM skill_mastery_state WHERE resume_id=? AND skill=?
                """, rs -> {
                    String component = rs.getString("knowledge_component");
                    result.put(component, new MasteryState(component,
                            rs.getBigDecimal("mastery_probability").doubleValue(),
                            rs.getInt("attempts"), rs.getInt("correct_count")));
                }, resumeId, JAVA);
        return result;
    }

    private double bktUpdate(double prior, boolean correct) {
        double mastered = Math.max(0.01, Math.min(0.99, prior));
        double posterior;
        if (correct) {
            double numerator = mastered * (1.0 - BKT_P_SLIP);
            double denominator = numerator + (1.0 - mastered) * BKT_P_GUESS;
            posterior = denominator == 0 ? mastered : numerator / denominator;
        } else {
            double numerator = mastered * BKT_P_SLIP;
            double denominator = numerator + (1.0 - mastered) * (1.0 - BKT_P_GUESS);
            posterior = denominator == 0 ? mastered : numerator / denominator;
        }
        double next = posterior + (1.0 - posterior) * BKT_P_TRANSITION;
        return Math.max(0.01, Math.min(0.99, next));
    }

    private double weightedMastery(Map<String, MasteryState> mastery, Map<String, Double> weights) {
        double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weightSum <= 0) return 0.0;
        double value = weights.entrySet().stream()
                .mapToDouble(entry -> mastery.get(entry.getKey()).probability() * entry.getValue())
                .sum() / weightSum;
        return round4(value);
    }

    private double bktConfidence(int questions, int topics, boolean expired) {
        double value = Math.min(0.82, 0.42 + questions * 0.025 + topics * 0.01);
        if (expired) value = Math.max(0.25, value - 0.15);
        return Math.round(value * 100) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private List<QuestionRow> sample(String level, int limit) {
        return jdbc.query("""
                SELECT id, level, topic, question_text, options_json, correct_option, explanation
                FROM skill_questions
                WHERE skill=? AND level=? AND active=TRUE AND verified=TRUE
                ORDER BY random() LIMIT ?
                """, (rs, rowNum) -> row(rs.getLong("id"), rs.getString("level"), rs.getString("topic"),
                rs.getString("question_text"), rs.getString("options_json"), rs.getString("correct_option"),
                rs.getString("explanation")), JAVA, level, limit);
    }

    private QuestionRow question(Long id) {
        List<QuestionRow> rows = jdbc.query("""
                SELECT id, level, topic, question_text, options_json, correct_option, explanation
                FROM skill_questions WHERE id=? AND active=TRUE
                """, (rs, rowNum) -> row(rs.getLong("id"), rs.getString("level"), rs.getString("topic"),
                rs.getString("question_text"), rs.getString("options_json"), rs.getString("correct_option"),
                rs.getString("explanation")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private QuestionRow row(Long id, String level, String topic, String text, String optionsJson,
                            String correct, String explanation) {
        try {
            List<SkillOption> options = objectMapper.readValue(optionsJson, new TypeReference<>() {});
            return new QuestionRow(id, level, topic, text, options, correct, explanation);
        } catch (Exception e) {
            throw new BusinessException("题库选项解析失败：" + e.getMessage());
        }
    }

    private List<Long> readIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException("测试题目标识解析失败");
        }
    }

    private double weight(String level) {
        return switch (level) {
            case "BASIC" -> 1.0;
            case "INTERMEDIATE" -> 2.0;
            case "ADVANCED" -> 3.0;
            default -> 1.0;
        };
    }

    private String classify(Map<String, int[]> stats) {
        double basic = accuracy(stats.get("BASIC"));
        double intermediate = accuracy(stats.get("INTERMEDIATE"));
        double advanced = accuracy(stats.get("ADVANCED"));
        if (basic >= 0.75 && intermediate >= 0.75 && advanced >= 0.50) return "高级";
        if (basic >= 0.75 && intermediate >= 0.50) return "中级";
        if (basic >= 0.50) return "初级";
        return "待提升";
    }

    private double accuracy(int[] stat) {
        return stat == null || stat[1] == 0 ? 0 : stat[0] * 1.0 / stat[1];
    }

    private double confidence(int questionCount, boolean expired) {
        double value = Math.min(0.85, 0.55 + questionCount * 0.03);
        if (expired) value = Math.max(0.30, value - 0.20);
        return Math.round(value * 100) / 100.0;
    }

    private LocalDateTime timestamp(Object value) {
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDateTime dt) return dt;
        return LocalDateTime.now();
    }

    private record QuestionRow(Long id, String level, String topic, String questionText,
                               List<SkillOption> options, String correctOption, String explanation) {}

    private record MasteryState(String knowledgeComponent, double probability, int attempts, int correctCount) {}
}