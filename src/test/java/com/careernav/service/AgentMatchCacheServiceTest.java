package com.careernav.service;

import com.careernav.domain.ApiModels.AgentMatchItem;
import com.careernav.domain.ApiModels.AgentMatchWeights;
import com.careernav.domain.ApiModels.InterviewSkillEvidence;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.JobEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentMatchCacheServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

    @Test
    void interviewEvidenceChangeInvalidatesCachedMatch() {
        AgentMatchCacheService cache = new AgentMatchCacheService();
        JobEntity job = job();
        AgentMatchItem item = new AgentMatchItem(1L, 80, 80, 80, "高度匹配", "缓存结果",
                List.of(), new AgentMatchWeights(40, 25, 10, 10, 5, 10), List.of(), "AGENT");

        ResumeView before = resume(80);
        cache.put(before, job, item);
        assertNotNull(cache.get(before, job));

        ResumeView afterInterview = resume(25);
        assertNull(cache.get(afterInterview, job), "面试证据变化后必须重新计算");
        cache.put(afterInterview, job, item);
        assertNotNull(cache.get(afterInterview, job));
    }

    @Test
    void explicitResumeInvalidationRemovesCachedMatches() {
        AgentMatchCacheService cache = new AgentMatchCacheService();
        JobEntity job = job();
        AgentMatchItem item = new AgentMatchItem(1L, 80, 80, 80, "高度匹配", "缓存结果",
                List.of(), new AgentMatchWeights(40, 25, 10, 10, 5, 10), List.of(), "AGENT");
        ResumeView resume = resume(80);
        cache.put(resume, job, item);
        assertNotNull(cache.get(resume, job));

        cache.invalidateResume(1L);

        assertNull(cache.get(resume, job), "取消学历验证后必须清除旧匹配缓存");
    }

    private JobEntity job() {
        JobEntity job = new JobEntity();
        job.setId(1L);
        job.setExternalId("job-1");
        job.setContentHash("content-v1");
        return job;
    }

    private ResumeView resume(int score) {
        return new ResumeView(
                1L, "测试学生", "Java后端开发", "长沙", null, null,
                "本科", "测试大学", "计算机科学与技术", 2026,
                null, null, null, null, null, null, null, null,
                List.of("Java"), List.of(), List.of(), "测试简历", "测试简历",
                List.of(new InterviewSkillEvidence("Java", score, "待验证", 0.8, false,
                        "INTERVIEW", List.of("回答证据"), NOW)),
                "UNVERIFIED", "未核验", null, null, false, null, NOW, NOW
        );
    }
}
