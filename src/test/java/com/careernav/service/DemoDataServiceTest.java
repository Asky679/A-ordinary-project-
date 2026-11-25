package com.careernav.service;

import com.careernav.entity.InterviewSessionEntity;
import com.careernav.entity.ResumeEntity;
import com.careernav.entity.UserEntity;
import com.careernav.repository.CandidateStoryRepository;
import com.careernav.repository.DiagnosisReportRepository;
import com.careernav.repository.EducationVerificationRepository;
import com.careernav.repository.InterviewSessionRepository;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.ResumeVersionRepository;
import com.careernav.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDataServiceTest {
    @Test
    void statsSumEveryRecordTypeAcrossDemoAccounts() {
        Fixture fixture = new Fixture();
        UserEntity first = demoUser(7L);
        UserEntity second = demoUser(8L);
        when(fixture.users.findByRole("DEMO")).thenReturn(List.of(first, second));
        when(fixture.resumes.countByUserId(7L)).thenReturn(2L);
        when(fixture.resumes.countByUserId(8L)).thenReturn(1L);
        when(fixture.interviews.countByUserId(7L)).thenReturn(3L);
        when(fixture.interviews.countByUserId(8L)).thenReturn(4L);
        when(fixture.reports.countByUserId(7L)).thenReturn(1L);
        when(fixture.reports.countByUserId(8L)).thenReturn(2L);
        when(fixture.stories.countByUserId(7L)).thenReturn(5L);
        when(fixture.stories.countByUserId(8L)).thenReturn(6L);

        Map<String, Object> stats = fixture.service.stats();

        assertEquals(2, stats.get("demoUsers"));
        assertEquals(3L, stats.get("resumes"));
        assertEquals(7L, stats.get("interviews"));
        assertEquals(3L, stats.get("reports"));
        assertEquals(11L, stats.get("stories"));
    }

    @Test
    void clearDeletesOnlyDemoAccountDataAndSkipsMissingAgentTables() {
        Fixture fixture = new Fixture();
        UserEntity demo = demoUser(9L);
        InterviewSessionEntity trashedSession = new InterviewSessionEntity();
        trashedSession.setId(44L);
        ResumeEntity resume = new ResumeEntity();
        resume.setId(12L);
        when(fixture.users.findByRole("DEMO")).thenReturn(List.of(demo));
        when(fixture.interviews.findByUserIdOrderByUpdatedAtDesc(9L)).thenReturn(List.of(trashedSession));
        when(fixture.stories.countByUserId(9L)).thenReturn(2L);
        when(fixture.reports.countByUserId(9L)).thenReturn(3L);
        when(fixture.resumes.findByUserIdOrderByUpdatedAtDesc(9L)).thenReturn(List.of(resume));
        when(fixture.jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);

        Map<String, Object> result = fixture.service.clear();

        assertEquals(1, result.get("interviews"));
        assertEquals(3, result.get("reports"));
        assertEquals(2, result.get("stories"));
        assertEquals(1, result.get("resumes"));
        verify(fixture.resumes).deleteByUserId(9L);
        verify(fixture.interviews).deleteByUserId(9L);
        verify(fixture.reports).deleteByUserId(9L);
        verify(fixture.stories).deleteByUserId(9L);
        verify(fixture.versions).deleteByResumeId(12L);
        verify(fixture.verifications).deleteByResumeId(12L);
        verify(fixture.jdbc, never()).update(anyString(), any(Object[].class));
        verify(fixture.resumes, never()).deleteByUserId(1L);
    }

    private static UserEntity demoUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setRole("DEMO");
        return user;
    }

    private static final class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final ResumeRepository resumes = mock(ResumeRepository.class);
        final InterviewSessionRepository interviews = mock(InterviewSessionRepository.class);
        final DiagnosisReportRepository reports = mock(DiagnosisReportRepository.class);
        final CandidateStoryRepository stories = mock(CandidateStoryRepository.class);
        final ResumeVersionRepository versions = mock(ResumeVersionRepository.class);
        final EducationVerificationRepository verifications = mock(EducationVerificationRepository.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final DemoDataService service = new DemoDataService(users, resumes, interviews, reports,
                stories, versions, verifications, jdbc);
    }
}
