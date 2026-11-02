package com.careernav.service;

import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.domain.ApiModels.SkillProficiencyView;
import com.careernav.entity.JobEntity;
import com.careernav.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JobServiceSkillVerificationTest {
    @Test
    void lowJavaTestScoreCannotSatisfyHardJavaRequirement() throws Exception {
        JobService service = service();
        ResumeView resume = resume(new SkillProficiencyView(
                1L, "Java", "自述", "待提升", 33, 0.85, "SKILL_TEST",
                0.4253, 0.76, LocalDateTime.now()));
        JobEntity job = javaJob();

        Method score = JobService.class.getDeclaredMethod(
                "score", ResumeView.class, JobEntity.class, java.util.Set.class, JobService.MatchWeights.class);
        score.setAccessible(true);
        var result = (com.careernav.domain.ApiModels.JobRecommendation) score.invoke(
                service, resume, job, java.util.Set.of("java"), new JobService.MatchWeights(34, 22, 16, 10, 4, 14));

        assertTrue(result.matchScore() <= 50);
        assertTrue(result.missingSkills().stream().anyMatch(skill -> skill.equalsIgnoreCase("Java")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("水平测试")));
    }

    @Test
    void agentEvidenceIsOverriddenByFailedSkillTest() throws Exception {
        JobService service = service();
        ResumeView resume = resume(new SkillProficiencyView(
                1L, "Java", "自述", "待提升", 33, 0.85, "SKILL_TEST",
                0.4253, 0.76, LocalDateTime.now()));
        JobEntity job = javaJob();
        var agent = new LangGraphMatchClient.AgentScore(
                1L, new com.careernav.domain.ApiModels.AgentMatchWeights(40, 25, 10, 5, 5, 15),
                "技能栏具备 Java 经历",
                List.of(new LangGraphMatchClient.Breakdown("熟练掌握 Java", "硬性", "满足", "技能栏有 Java")));

        Method build = JobService.class.getDeclaredMethod(
                "buildAgentMatch", ResumeView.class, JobEntity.class, LangGraphMatchClient.AgentScore.class);
        build.setAccessible(true);
        var result = (com.careernav.domain.ApiModels.AgentMatchItem) build.invoke(service, resume, job, agent);

        assertEquals("不满足", result.breakdown().getFirst().status());
        assertTrue(result.reason().contains("水平测试"));
        assertTrue(result.finalScore() <= 50);
    }

    private JobService service() {
        return new JobService(mock(JobRepository.class), new JsonCodec(new ObjectMapper()),
                mock(LangGraphMatchClient.class), mock(AgentMatchCacheService.class), new AhpWeightService());
    }

    private JobEntity javaJob() {
        JobEntity job = new JobEntity();
        job.setId(1L);
        job.setTitle("Java后端开发工程师");
        job.setCompany("测试公司");
        job.setCity("长沙");
        job.setEducation("本科");
        job.setExperience("经验不限");
        job.setSkillsJson("[\"Java\"]");
        return job;
    }

    private ResumeView resume(SkillProficiencyView java) {
        LocalDateTime now = LocalDateTime.now();
        return new ResumeView(
                1L, "测试学生", "Java后端开发", "长沙", null, null,
                "博士", "测试大学", "计算机科学与技术", 2026,
                List.of("Java"), List.of(new ProjectItem("测试项目", "后端", "Java", "接口开发", "支持1000用户")),
                List.<ExperienceItem>of(), "测试简历", "测试简历", List.of(java),
                "CONSISTENT", "信息一致", 100, 90, true, now, now, now
        );
    }
}