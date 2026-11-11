package com.careernav.service;

import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.InterviewMessage;
import com.careernav.domain.ApiModels.InterviewSkillEvidence;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewSkillEvidenceScopeTest {
    @Test
    void onlySkillsActuallyMentionedInInterviewAreAssessed() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper().findAndRegisterModules());
        InterviewSkillEvidenceService service = new InterviewSkillEvidenceService(
                mock(InterviewSessionRepository.class), jsonCodec, model);

        List<InterviewMessage> messages = List.of(
                new InterviewMessage("assistant", "请解释 Java 的核心机制和项目使用方式。", LocalDateTime.now(), null, null),
                new InterviewMessage("user", "我在订单项目里使用 Java 和 Spring Boot 完成接口开发。", LocalDateTime.now(), 65, ""));
        List<InterviewSkillEvidence> result = service.assess(resume(), job(), messages, 60, null);

        assertTrue(result.stream().anyMatch(item -> item.skill().equals("Java")));
        assertFalse(result.stream().anyMatch(item -> item.skill().equals("PostgreSQL")));
        assertFalse(result.stream().anyMatch(item -> item.skill().equals("HTML5")));
    }

    @Test
    void historicalUnaskedSkillsAreNotReturnedAsFailedEvidence() {
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper().findAndRegisterModules());
        InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
        InterviewSkillEvidenceService service = new InterviewSkillEvidenceService(
                repository, jsonCodec, mock(AgentModelClient.class));
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(1L);
        session.setResumeId(1L);
        session.setStatus("COMPLETED");
        session.setUpdatedAt(LocalDateTime.now());
        session.setMessagesJson(jsonCodec.write(List.of(
                new InterviewMessage("assistant", "请讲 Java 项目实现。", LocalDateTime.now(), null, null),
                new InterviewMessage("user", "我用 Java 完成了订单接口。", LocalDateTime.now(), 70, ""))));
        session.setSkillEvidenceJson(jsonCodec.write(List.of(
                new InterviewSkillEvidence("Java", 70, "中级", 0.8, true, "INTERVIEW",
                        List.of("回答中说明了 Java 项目实现"), LocalDateTime.now()),
                new InterviewSkillEvidence("PostgreSQL", 20, "待验证", 0.8, false, "INTERVIEW",
                        List.of("面试未形成明确证据：未提及 PostgreSQL"), LocalDateTime.now()))));
        when(repository.findByResumeIdAndStatusOrderByUpdatedAtDesc(1L, "COMPLETED"))
                .thenReturn(List.of(session));

        List<InterviewSkillEvidence> result = service.latestForResume(1L);

        assertTrue(result.stream().anyMatch(item -> item.skill().equals("Java")));
        assertFalse(result.stream().anyMatch(item -> item.skill().equals("PostgreSQL")));
    }

    private ResumeView resume() {
        LocalDateTime now = LocalDateTime.now();
        return new ResumeView(
                1L, "测试学生", "Java后端", "长沙", null, null,
                "本科", "测试大学", "软件工程", 2027,
                null, null, null, null, null, null, null, null,
                List.of("Java", "PostgreSQL", "HTML5"),
                List.of(new ProjectItem("订单系统", "后端", "Java、MySQL", "订单链路", "重复提交", "接口测试")),
                List.<ExperienceItem>of(), "测试简历", "测试简历", List.of(),
                "CONSISTENT", "二维码核验通过", 100, 95, true, now, now, now);
    }

    private JobView job() {
        return new JobView(
                1L, "job-1", "软件工程师", "测试公司", "长沙", "10-15K", "本科", "经验不限",
                List.of(), List.of("Java 后端开发"), "Java 后端开发", "Java 后端开发",
                "熟悉 Java", "ORIGINAL", null, null, "测试来源", null, null, null,
                null, null, "ACTIVE", "后端开发", "互联网", "100-499人", "长沙");
    }
}
