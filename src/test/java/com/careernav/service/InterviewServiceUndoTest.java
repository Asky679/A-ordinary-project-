package com.careernav.service;

import com.careernav.domain.ApiModels.InterviewMessage;
import com.careernav.domain.ApiModels.InterviewView;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewServiceUndoTest {
    @Test
    void undoRemovesLastAnswerAndRestoresPreviousQuestion() {
        InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
        LangGraphInterviewClient langGraph = mock(LangGraphInterviewClient.class);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonCodec codec = new JsonCodec(mapper);
        InterviewService service = new InterviewService(
                repository, mock(ResumeService.class), mock(JobService.class), codec,
                mock(AgentModelClient.class), langGraph, mock(InterviewSkillEvidenceService.class),
                mock(JobInterviewBlueprintService.class), mock(CandidateStoryService.class));

        LocalDateTime now = LocalDateTime.now();
        List<InterviewMessage> messages = new ArrayList<>(List.of(
                new InterviewMessage("assistant", "请介绍项目职责。", now, null, null),
                new InterviewMessage("user", "我负责订单接口。", now, 70, "回答较具体。"),
                new InterviewMessage("assistant", "请说明业务链路。", now, null, null),
                new InterviewMessage("user", "从 Controller 到数据库。", now, 80, "链路清楚。"),
                new InterviewMessage("assistant", "请说明异常边界。", now, null, null)
        ));
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setId(12L);
        entity.setResumeId(1L);
        entity.setJobId(1L);
        entity.setJobTitle("Java后端实习");
        entity.setInterviewMode("技术面");
        entity.setDifficulty("初级 · 标准");
        entity.setStatus("IN_PROGRESS");
        entity.setCurrentRound(3);
        entity.setTotalRounds(7);
        entity.setOverallScore(75);
        entity.setMessagesJson(codec.write(messages));
        entity.setStartedAt(now);
        entity.setUpdatedAt(now);

        when(repository.findById(12L)).thenReturn(Optional.of(entity));
        when(repository.save(any(InterviewSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(langGraph.available()).thenReturn(true);
        when(langGraph.undo(12L)).thenReturn(true);

        InterviewView view = service.undo(12L);

        assertEquals(2, view.currentRound());
        assertEquals("IN_PROGRESS", view.status());
        assertEquals(70, view.overallScore());
        assertEquals(3, view.messages().size());
        assertEquals("请说明业务链路。", view.messages().get(view.messages().size() - 1).content());
        assertTrue(view.messages().stream().noneMatch(message -> "从 Controller 到数据库。".equals(message.content())));
        verify(langGraph).undo(12L);
    }
}
