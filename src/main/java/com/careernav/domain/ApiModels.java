package com.careernav.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record ProjectItem(@NotBlank(message = "项目名称不能为空") String name, String role,
                              String techStack, String description, String challenge, String highlight) {
        public ProjectItem(String name, String role, String techStack, String description, String highlight) {
            this(name, role, techStack, description, null, highlight);
        }
    }

    public record ExperienceItem(String company, String position, String period,
                                 String description, String achievement) {}

    public record ResumePayload(@NotBlank(message = "姓名不能为空") String ownerName,
                                @NotBlank(message = "目标岗位不能为空") String targetRole,
                                String city, String phone, String email, String education,
                                String school, String major, Integer graduationYear,
                                String educationCategory, String duration, String studyMode,
                                String college, String department, String enrollmentDate,
                                String studentStatus, String expectedGraduationDate,
                                List<String> skills, @Valid List<ProjectItem> projects,
                                @Valid List<ExperienceItem> experiences,
                                String selfEvaluation, String rawText) {}

    /** OCR / PDF 导入草稿：只回填编辑器，用户确认后才写入简历仓库。 */
    public record ResumeImportResult(String ownerName, String targetRole, String city, String phone,
                                     String email, String education, String school, String major,
                                     Integer graduationYear, List<String> skills,
                                     List<ProjectItem> projects, List<ExperienceItem> experiences,
                                     String selfEvaluation, String rawText, String extractionMethod,
                                     int confidence, List<String> warnings, boolean needsReview) {}

    /** 创建简历版本：label 为版本类型，targetJobId 可选（用于绑定当时的岗位与匹配分）。 */
    /** ---------- Agent 岗位匹配 ---------- */
    public record AgentMatchRequest(Long resumeId, java.util.List<Long> jobIds) {}

    public record MatchBreakdown(String requirement, String importance, String status, String evidence) {}

    /** Agent 只提供受限权重；最终分数由后端确定性规则计算。 */
    public record AgentMatchWeights(int skills, int role, int experience,
                                    int education, int city, int evidence) {}

    /** AHP 六维基准权重及其一致性校验结果。 */
    public record AhpCriterionWeight(String key, String label, double weight, int weightPercent) {}

    public record AhpWeightProfile(String method, String scale, Long jobId, String jobTitle,
                                   boolean jobAdjusted, List<AhpCriterionWeight> criteria,
                                   List<List<Double>> pairwiseMatrix, double principalEigenvalue,
                                   double consistencyIndex, double consistencyRatio, double randomIndex,
                                   boolean consistent, double weightSum, AgentMatchWeights baselineWeights,
                                   List<String> adjustmentReasons, String note) {}

    public record AgentMatchItem(Long jobId, int ruleScore, int agentScore, int finalScore,
                                 String verdict, String reason, List<MatchBreakdown> breakdown,
                                 AgentMatchWeights weights, List<MatchScoreComponent> scoreBreakdown,
                                 String scoreSource) {}

    public record AgentMatchResponse(boolean available, java.util.List<AgentMatchItem> items) {}

    /** ---------- 学历材料一致性核验 ---------- */
    public record EducationVerificationCheck(String key, String label, String resumeValue,
                                             String proofValue, String status, String detail) {}

    public record EducationVerificationResult(Long resumeId, String status, String statusLabel,
                                               boolean passed, int score, int confidence, String reportType,
                                               boolean officialReportDetected, String proofFileType,
                                               String proofHash, String extractionMode, String summary,
                                               List<EducationVerificationCheck> checks,
                                               boolean qrDetected, String qrStatus, String qrStatusLabel,
                                               String qrContent, String qrVerificationCode, String officialVerifyUrl,
                                               String qrImageDataUrl, boolean officialConfirmed,
                                               String proofOwnerName, String proofSchool, String proofEducation,
                                               String proofMajor, Integer proofGraduationYear,
                                               String proofEducationCategory, String proofDuration,
                                               String proofStudyMode, String proofCollege, String proofDepartment,
                                               String proofEnrollmentDate, String proofStudentStatus,
                                               String proofExpectedGraduationDate,
                                               List<String> warnings, LocalDateTime checkedAt) {}

    /** ---------- ATS 检查 ---------- */
    public record AtsIssue(String level, String category, String issue, String action) {}

    public record AtsBreakdown(int parseability, int structure, int keywords, int content) {}

    public record AtsReport(int atsScore, String level, AtsBreakdown breakdown,
                            List<AtsIssue> issues, java.util.Map<String, Object> parsed) {}

    /** ---------- 引导式简历编写 ---------- */
    public record SkillStat(String skill, int count, int percent) {}

    public record DirectionProfile(String role, int jobCount, List<SkillStat> topSkills) {}

    public record SkillEvidence(String skill, boolean evidenced, String location) {}

    public record QualityScore(int total, int completeness, int coverage, int evidence, int quantified,
                               List<String> suggestions) {}

    public record GuidanceQuestion(String key, String label, String hint, String example) {}

    public record GuidanceReport(Long resumeId, String role, DirectionProfile profile,
                                 List<String> matchedSkills, List<String> missingSkills,
                                 List<SkillEvidence> evidences, QualityScore quality,
                                 List<GuidanceQuestion> questions) {}

    public record ProjectDraftRequest(String role, String background, String responsibility,
                                      String techStack, String challenge, String result, boolean useAi) {}

    public record ProjectDraft(String description, String highlight, String mode, List<String> facts) {}

    public record ResumeVersionRequest(String label, Long targetJobId, String note) {}

    /** 版本详情：快照反序列化为结构化简历，便于前端直接展示与对比。 */
    public record ResumeVersionView(Long id, Long resumeId, Integer versionNo, String label,
                                    Long targetJobId, String targetJobTitle, Integer matchScore,
                                    String note, List<String> factLocks, LocalDateTime createdAt,
                                    ResumePayload snapshot) {}

    /** 事实锁设置：fields 为字段 key 列表，空列表表示全部解锁。 */
    public record FactLockRequest(List<String> fields) {}

    public record ResumeFieldDiff(String field, String label, String left, String right) {}

    public record ResumeVersionCompare(ResumeVersionView left, ResumeVersionView right,
                                       List<ResumeFieldDiff> diffs) {}

    public record ResumeView(Long id, String ownerName, String targetRole, String city, String phone,
                             String email, String education, String school, String major,
                             Integer graduationYear, String educationCategory, String duration,
                             String studyMode, String college, String department, String enrollmentDate,
                             String studentStatus, String expectedGraduationDate,
                             List<String> skills, List<ProjectItem> projects,
                             List<ExperienceItem> experiences, String selfEvaluation, String rawText,
                             List<InterviewSkillEvidence> interviewSkillEvidence,
                             String educationVerificationStatus, String educationVerificationLabel,
                             Integer educationVerificationScore, Integer educationVerificationConfidence,
                             boolean educationVerified, LocalDateTime educationVerificationCheckedAt,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record JobView(Long id, String externalId, String title, String company, String city,
                          String salary, String education, String experience, List<String> skills,
                          List<String> responsibilities, String description, String jobDescription,
                          String jobRequirements, String contentOrigin, String aiModel, String aiConfidence, String source,
                          String sourceUrl, LocalDateTime collectedAt, LocalDateTime publishedAt,
                          LocalDateTime validThrough, LocalDateTime lastSeenAt, String status,
                          String category, String industry, String companySize, String location) {}

    public record MatchScoreComponent(String key, String label, double weight, double factor,
                                      int contribution, String detail) {}

    public record JobRecommendation(JobView job, int matchScore, String matchLevel,
                                    List<String> matchedSkills, List<String> missingSkills,
                                    List<String> reasons, List<MatchScoreComponent> scoreBreakdown) {}

    /** 城市岗位库：count 为该城市 ACTIVE 岗位总数。 */
    public record CityJobCount(String city, long count) {}

    /** 城市全量岗位库分页结果，page 从 0 开始。 */
    public record CityJobPage(String city, long total, int page, int size,
                              List<JobRecommendation> items) {}

    /** 面试 Agent 形成的技能熟练度证据。 */
    public record InterviewSkillEvidence(String skill, int score, String level, double confidence,
                                         boolean verified, String source, List<String> evidence,
                                         LocalDateTime updatedAt) {}

    /** 从岗位 JD 提取的面试蓝图，用于约束面试覆盖范围。 */
    public record JobInterviewBlueprint(Long jobId, String title, String company, String roleDirection,
                                        String seniority, String domain,
                                        List<String> mustHaveSkills, List<String> niceToHaveSkills,
                                        List<String> coreResponsibilities, List<String> interviewScenarios,
                                        List<String> hardConstraints, String companyStyle,
                                        List<String> coveragePlan, String sourceSummary) {}

    /** 脱敏项目证据卡：企业项目不需要上传源码。 */
    public record InterviewProjectEvidence(String projectName, String role, String teamSize,
                                           String businessFlow, String techStack, String implementation,
                                           String challenge, String resultEvidence, String confidentiality,
                                           String notes) {}

    public record InterviewStartRequest(@NotNull(message = "请选择简历") Long resumeId,
                                        @NotNull(message = "请选择目标岗位") Long jobId,
                                        String difficulty,
                                        @Valid InterviewProjectEvidence projectEvidence) {
        public InterviewStartRequest(Long resumeId, Long jobId, String difficulty) {
            this(resumeId, jobId, difficulty, null);
        }
    }

    public record InterviewAnswerRequest(@NotBlank(message = "回答不能为空") String answer) {}

    public record InterviewMessage(String role, String content, LocalDateTime timestamp,
                                   Integer score, String feedback) {}

    public record InterviewView(Long id, Long resumeId, Long jobId, String jobTitle,
                                String difficulty, String status, int currentRound, int totalRounds,
                                int overallScore, List<InterviewMessage> messages,
                                List<InterviewSkillEvidence> skillEvidence,
                                InterviewProjectEvidence projectEvidence,
                                JobInterviewBlueprint jobBlueprint,
                                LocalDateTime startedAt, LocalDateTime updatedAt) {}

    public record DiagnosisRequest(Long resumeId, Long jobId) {}

    public record DiagnosisDimension(String name, int score, int weight, String comment) {}

    public record DiagnosisSuggestion(String priority, String category, String issue,
                                      String action, String example) {}

    public record DiagnosisReport(Long id, Long resumeId, Long jobId, String targetRole,
                                  int overallScore, int resumeQualityScore, Integer jobMatchScore,
                                  String jobMatchLevel, String level, String summary,
                                  List<DiagnosisDimension> dimensions,
                                  List<MatchScoreComponent> jobMatchBreakdown,
                                  List<String> strengths,
                                  List<String> matchedKeywords, List<String> missingKeywords,
                                  List<DiagnosisSuggestion> suggestions,
                                  List<String> agentTrace,
                                  LearningPath learningPlan,
                                  String jobMatchSource, Integer ruleMatchScore, Integer agentMatchScore,
                                  List<MatchBreakdown> agentMatchBreakdown,
                                  LocalDateTime resumeUpdatedAt, LocalDateTime createdAt) {}

    public record LearningPathRequest(@NotNull(message = "请选择简历") Long resumeId,
                                      String targetRole) {}

    public record LearningModule(int order, String title, String duration, String objective,
                                 List<String> topics, String deliverable, String status) {}

    public record LearningPath(Long resumeId, String targetRole, int readiness,
                               List<String> currentSkills, List<String> targetSkills,
                               List<String> skillGaps, List<LearningModule> modules, String summary) {}

    public record SkillNode(String id, String label, String level, String category,
                            int demand, boolean owned) {}

    public record SkillLink(String source, String target) {}

    public record SkillGraph(String role, List<SkillNode> nodes, List<SkillLink> links,
                             Map<String, Integer> categoryDemand) {}

    public record AgentStep(int order, String tool, String title, String status,
                            long durationMs, String detail) {}

    public record AgentRunRequest(@NotNull(message = "请选择简历") Long resumeId,
                                  Long jobId, String intent) {}

    public record AgentRunResult(String runId, String intent, String mode, String summary,
                                 List<AgentStep> steps, DiagnosisReport diagnosis,
                                 List<JobRecommendation> recommendations, LearningPath learningPath, InterviewView interview,
                                 LocalDateTime createdAt) {}

    public record MarketSnapshotView(Long id, String monthKey, long totalJobs, long activeJobs,
                                     Map<String, Object> cityDemand, Map<String, Object> skillDemand,
                                     Map<String, Object> salarySummary, LocalDateTime createdAt) {}

    public record DashboardStats(long resumeCount, long jobCount, long interviewCount,
                                 long reportCount, int averageDiagnosisScore,
                                 int averageMatchScore) {}

    public record DashboardView(DashboardStats stats, List<String> workflow,
                                List<JobRecommendation> topJobs, DiagnosisReport latestDiagnosis,
                                String agentMode, boolean mcpOnline) {}
}
