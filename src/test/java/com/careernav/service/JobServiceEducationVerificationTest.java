package com.careernav.service;

import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.JobRecommendation;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.JobEntity;
import com.careernav.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JobServiceEducationVerificationTest {
    @Test
    void unverifiedEducationIsTreatedAsUnqualifiedAndCapped() throws Exception {
        JobRecommendation result = score(false, "UNVERIFIED");
        assertTrue(result.matchScore() <= 55);
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("学历材料未通过真实性核验")));
    }

    @Test
    void mismatchedEducationIsTreatedAsUnqualifiedAndCapped() throws Exception {
        JobRecommendation result = score(false, "MISMATCH");
        assertTrue(result.matchScore() <= 55);
        assertTrue(result.scoreBreakdown().stream().anyMatch(item ->
                "education".equals(item.key()) && item.factor() == 0.0));
    }

    @Test
    void verifiedEducationCanUseNormalEducationScore() throws Exception {
        JobRecommendation result = score(true, "CONSISTENT");
        assertTrue(result.matchScore() > 55);
        assertTrue(result.scoreBreakdown().stream().anyMatch(item ->
                "education".equals(item.key()) && item.factor() == 1.0));
    }

    private JobRecommendation score(boolean verified, String verificationStatus) throws Exception {
        JobService service = new JobService(
                mock(JobRepository.class), new JsonCodec(new ObjectMapper()),
                mock(LangGraphMatchClient.class), mock(AgentMatchCacheService.class),
                new AhpWeightService());
        LocalDateTime now = LocalDateTime.now();
        ResumeView resume = new ResumeView(
                1L, "测试学生", "Java后端开发", "长沙", null, null,
                "博士", "测试大学", "计算机科学与技术", 2026,
                List.of("Java"), List.of(new ProjectItem("测试项目", "后端", "Java", "接口开发", "支持1000用户")),
                List.<ExperienceItem>of(), "测试简历", "测试简历", List.of(),
                verificationStatus, verified ? "信息一致" : "未核验",
                verified ? 100 : null, verified ? 90 : null, verified, verified ? now : null,
                now, now
        );
        JobEntity job = new JobEntity();
        job.setId(1L);
        job.setTitle("Java后端开发工程师");
        job.setCompany("测试公司");
        job.setCity("长沙");
        job.setEducation("本科");
        job.setExperience("经验不限");
        job.setSkillsJson("[\"Java\"]");

        Method score = JobService.class.getDeclaredMethod(
                "score", ResumeView.class, JobEntity.class, Set.class, JobService.MatchWeights.class);
        score.setAccessible(true);
        return (JobRecommendation) score.invoke(
                service, resume, job, Set.of("java"), new JobService.MatchWeights(34, 22, 16, 10, 4, 14));
    }
}