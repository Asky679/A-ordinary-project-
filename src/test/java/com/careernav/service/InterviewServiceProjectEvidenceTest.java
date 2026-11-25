package com.careernav.service;

import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.InterviewProjectEvidence;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InterviewServiceProjectEvidenceTest {
    @Test
    void fillsProjectEvidenceFromResumeWithoutSourceCode() throws Exception {
        InterviewService service = service();
        ResumeView resume = resume();

        Method method = InterviewService.class.getDeclaredMethod(
                "projectEvidence", InterviewProjectEvidence.class, ResumeView.class);
        method.setAccessible(true);
        InterviewProjectEvidence evidence = (InterviewProjectEvidence) method.invoke(service, null, resume);

        assertEquals("企业订单系统", evidence.projectName());
        assertEquals("后端开发", evidence.role());
        assertTrue(evidence.techStack().contains("Redis"));
        assertTrue(evidence.challenge().contains("重复下单"));
        assertEquals("企业保密项目", evidence.confidentiality());
    }


    @Test
    void preservesStructuredEvidenceDetailsSubmittedByCandidate() throws Exception {
        InterviewProjectEvidence supplied = new InterviewProjectEvidence(
                "企业订单系统", "后端开发", "5人", "入口到返回", "Java、Redis",
                "订单接口", "重复下单", "测试通过", "企业保密项目", "",
                Map.of("ownedModules", "独立实现订单接口", "coreApi", "POST /orders"));
        Method method = InterviewService.class.getDeclaredMethod(
                "projectEvidence", InterviewProjectEvidence.class, ResumeView.class);
        method.setAccessible(true);
        InterviewProjectEvidence evidence = (InterviewProjectEvidence) method.invoke(service(), supplied, resume());
        assertEquals("独立实现订单接口", evidence.evidenceDetails().get("ownedModules"));
        assertEquals("POST /orders", evidence.evidenceDetails().get("coreApi"));
    }

    @Test
    void firstQuestionAsksForProjectOwnershipAndNeverRequiresCode() throws Exception {
        InterviewService service = service();
        InterviewProjectEvidence evidence = new InterviewProjectEvidence(
                "企业订单系统", "后端开发", "5人", "下单到支付", "Java、Redis",
                "订单服务和幂等处理", "重复回调", "响应时间降低30%", "企业保密项目", "");

        Method method = InterviewService.class.getDeclaredMethod(
                "firstQuestion", ResumeView.class, com.careernav.domain.ApiModels.JobView.class,
                String.class, InterviewProjectEvidence.class);
        method.setAccessible(true);
        String question = (String) method.invoke(service, resume(), job(), "标准", evidence);

        assertTrue(question.contains("企业订单系统"));
        assertTrue(question.contains("独立完成"));
        assertTrue(question.contains("不需要提供源码"));
    }

    @Test
    void comprehensiveInterviewStartsWithMotivationInsteadOfTechnicalDrill() throws Exception {
        InterviewService service = service();
        InterviewProjectEvidence evidence = new InterviewProjectEvidence(
                "企业订单系统", "后端开发", "5人", "下单到支付", "Java、Redis",
                "订单服务和幂等处理", "重复回调", "响应时间降低30%", "企业保密项目", "");

        Method method = InterviewService.class.getDeclaredMethod(
                "firstQuestion", ResumeView.class, com.careernav.domain.ApiModels.JobView.class,
                String.class, String.class, InterviewProjectEvidence.class);
        method.setAccessible(true);
        String question = (String) method.invoke(service, resume(), job(), "初级 · 标准", "综合面", evidence);

        assertTrue(question.contains("为什么选择"));
        assertTrue(!question.contains("请具体说明订单状态"));
    }

    @Test
    void juniorTechnicalQuestionsChangeByJobDirection() throws Exception {
        InterviewService service = service();
        Method method = InterviewService.class.getDeclaredMethod(
                "juniorQuestionForStage", String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        String javaQuestion = (String) method.invoke(service, "技术实现", "Java后端", "订单系统",
                "后端开发", "订单接口", "Java", "测试公司", "Java开发", "长沙", "接口开发");
        String frontendQuestion = (String) method.invoke(service, "技术实现", "前端", "订单系统",
                "前端开发", "订单页面", "Vue", "测试公司", "前端开发", "长沙", "页面开发");
        String testQuestion = (String) method.invoke(service, "技术实现", "测试", "订单系统",
                "测试开发", "订单测试", "Selenium", "测试公司", "测试工程师", "长沙", "接口测试");

        assertNotEquals(javaQuestion, frontendQuestion);
        assertNotEquals(javaQuestion, testQuestion);
        assertTrue(javaQuestion.contains("Controller"));
        assertTrue(frontendQuestion.contains("页面状态"));
        assertTrue(testQuestion.contains("测试用例"));
    }

    private InterviewService service() {
        return new InterviewService(
                mock(InterviewSessionRepository.class), mock(ResumeService.class), mock(JobService.class),
                new JsonCodec(new ObjectMapper()), mock(AgentModelClient.class),
                mock(LangGraphInterviewClient.class), mock(InterviewSkillEvidenceService.class),
                mock(JobInterviewBlueprintService.class), mock(CandidateStoryService.class));
    }

    private com.careernav.domain.ApiModels.JobView job() {
        return new com.careernav.domain.ApiModels.JobView(
                1L, "job-1", "Java后端开发", "测试公司", "长沙", "15-25K", "本科", "1-3年",
                List.of("Java", "Redis"), List.of("订单系统开发"), "订单系统开发", "负责订单系统",
                "熟悉 Java、Redis", "ORIGINAL", null, null, "测试来源", null, null, null,
                null, null, "ACTIVE", "后端开发", "互联网", "100-499人", "长沙");
    }

    private ResumeView resume() {
        LocalDateTime now = LocalDateTime.now();
        return new ResumeView(
                1L, "测试学生", "Java后端开发", "长沙", "13800138000", "test@example.com",
                "本科", "测试大学", "软件工程", 2027,
                null, null, null, null, null, null, null, null,
                List.of("Java", "Redis"),
                List.of(new ProjectItem("企业订单系统", "后端开发", "Java、Redis", "下单到支付",
                        "重复下单和支付回调", "响应时间降低30%")),
                List.<ExperienceItem>of(), "测试简历", "测试简历", List.of(),
                "CONSISTENT", "二维码核验通过", 100, 95, true, now, now, now);
    }
}
