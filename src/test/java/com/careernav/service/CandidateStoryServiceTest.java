package com.careernav.service;

import com.careernav.domain.ApiModels.InterviewMessage;
import com.careernav.entity.CandidateStoryEntity;
import com.careernav.entity.InterviewSessionEntity;
import com.careernav.repository.CandidateStoryRepository;
import com.careernav.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateStoryServiceTest {
    @Test
    void extractsFallbackStoryFromCandidateAnswers() {
        CandidateStoryRepository repository = mock(CandidateStoryRepository.class);
        InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        when(repository.existsBySourceSessionIdAndDeletedAtIsNull(8L)).thenReturn(false);
        when(repository.findFirstByResumeIdAndFingerprintAndDeletedAtIsNull(eq(1L), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(CandidateStoryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CandidateStoryService service = new CandidateStoryService(
                repository, sessions, new JsonCodec(new ObjectMapper()), model);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(8L);
        session.setResumeId(1L);
        session.setJobId(9L);
        session.setJobTitle("Java开发");
        session.setInterviewMode("行为面");
        session.setCurrentRound(8);
        List<InterviewMessage> messages = List.of(
                new InterviewMessage("assistant", "请讲一次冲突", LocalDateTime.now(), null, null),
                new InterviewMessage("user", "我和队友在分工上冲突，我先沟通，再主动承担联调，最后按时完成。", LocalDateTime.now(), 78, "有行动")
        );

        service.ingestCompletedSession(session, null, null, messages);

        ArgumentCaptor<CandidateStoryEntity> captor = ArgumentCaptor.forClass(CandidateStoryEntity.class);
        verify(repository).save(captor.capture());
        CandidateStoryEntity story = captor.getValue();
        assertEquals(1L, story.getResumeId());
        assertEquals(8L, story.getSourceSessionId());
        assertEquals("行为面", story.getInterviewMode());
        assertNotNull(story.getAction());
        assertEquals(45, story.getConfidence());
        assertEquals(0, story.getUsageCount());
    }

    @Test
    void fingerprintIsStableAcrossWhitespaceAndPunctuation() {
        String first = CandidateStoryService.fingerprint(1L, "校园项目", "订单幂等", "我负责 接口，增加唯一约束。");
        String second = CandidateStoryService.fingerprint(1L, "校园项目", "订单幂等", "我负责接口 增加唯一约束");
        assertEquals(first, second);
    }
}
