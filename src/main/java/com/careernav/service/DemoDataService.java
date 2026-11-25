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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DemoDataService {
    private final UserRepository users;
    private final ResumeRepository resumes;
    private final InterviewSessionRepository interviews;
    private final DiagnosisReportRepository reports;
    private final CandidateStoryRepository stories;
    private final ResumeVersionRepository versions;
    private final EducationVerificationRepository verifications;
    private final JdbcTemplate jdbc;

    public DemoDataService(UserRepository users, ResumeRepository resumes,
                           InterviewSessionRepository interviews,
                           DiagnosisReportRepository reports,
                           CandidateStoryRepository stories,
                           ResumeVersionRepository versions,
                           EducationVerificationRepository verifications,
                           JdbcTemplate jdbc) {
        this.users = users;
        this.resumes = resumes;
        this.interviews = interviews;
        this.reports = reports;
        this.stories = stories;
        this.versions = versions;
        this.verifications = verifications;
        this.jdbc = jdbc;
    }

    @Transactional
    public void assignUnownedLegacyData(Long demoUserId) {
        resumes.assignUnownedToDemo(demoUserId);
        interviews.assignUnownedToDemo(demoUserId);
        reports.assignUnownedToDemo(demoUserId);
        stories.assignUnownedToDemo(demoUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<UserEntity> demoUsers = users.findByRole("DEMO");
        long resumeCount = 0;
        long interviewCount = 0;
        long reportCount = 0;
        long storyCount = 0;
        for (UserEntity user : demoUsers) {
            Long userId = user.getId();
            resumeCount += resumes.countByUserId(userId);
            interviewCount += interviews.countByUserId(userId);
            reportCount += reports.countByUserId(userId);
            storyCount += stories.countByUserId(userId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("demoUsers", demoUsers.size());
        result.put("resumes", resumeCount);
        result.put("interviews", interviewCount);
        result.put("reports", reportCount);
        result.put("stories", storyCount);
        return result;
    }

    @Transactional
    public Map<String, Object> clear() {
        List<UserEntity> demoUsers = users.findByRole("DEMO");
        int deletedInterviews = 0;
        int deletedReports = 0;
        int deletedStories = 0;
        int deletedResumes = 0;
        for (UserEntity user : demoUsers) {
            Long userId = user.getId();
            List<InterviewSessionEntity> sessions = interviews.findByUserIdOrderByUpdatedAtDesc(userId);
            for (InterviewSessionEntity session : sessions) {
                deleteAgentState(String.valueOf(session.getId()));
            }
            deletedInterviews += sessions.size();

            deletedStories += (int) stories.countByUserId(userId);
            stories.deleteByUserId(userId);
            deletedReports += (int) reports.countByUserId(userId);
            reports.deleteByUserId(userId);
            interviews.deleteByUserId(userId);

            List<ResumeEntity> userResumes = resumes.findByUserIdOrderByUpdatedAtDesc(userId);
            deletedResumes += userResumes.size();
            for (ResumeEntity resume : userResumes) {
                verifications.deleteByResumeId(resume.getId());
                versions.deleteByResumeId(resume.getId());
            }
            resumes.deleteByUserId(userId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumes", deletedResumes);
        result.put("interviews", deletedInterviews);
        result.put("reports", deletedReports);
        result.put("stories", deletedStories);
        return result;
    }

    /**
     * SQL errors abort the surrounding PostgreSQL transaction. Probe the
     * optional Agent tables before deleting, instead of catching missing-table
     * errors inside the loop.
     */
    private void deleteAgentState(String threadId) {
        deleteIfTableExists("langgraph_interview_states", "session_id", threadId);
        deleteIfTableExists("checkpoint_writes", "thread_id", threadId);
        deleteIfTableExists("checkpoint_blobs", "thread_id", threadId);
        deleteIfTableExists("checkpoints", "thread_id", threadId);
    }

    private void deleteIfTableExists(String table, String column, String value) {
        Boolean present = jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table);
        if (Boolean.TRUE.equals(present)) {
            jdbc.update("DELETE FROM " + table + " WHERE " + column + " = ?", value);
        }
    }
}
