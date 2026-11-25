package com.careernav.service;

import com.careernav.domain.ApiModels.JobPage;
import com.careernav.entity.JobEntity;
import com.careernav.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobServiceBrowsePageTest {
    @Test
    void browsePaginatesBeyondFirstHundredJobs() {
        JobRepository repository = mock(JobRepository.class);
        JobService service = new JobService(
                repository, new JsonCodec(new ObjectMapper()), mock(LangGraphMatchClient.class),
                mock(AgentMatchCacheService.class), mock(AhpWeightService.class));
        List<JobEntity> jobs = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now();
        for (int index = 0; index < 130; index++) {
            JobEntity job = new JobEntity();
            job.setId((long) index + 1);
            job.setTitle("Java开发" + index);
            job.setCompany("测试公司" + index);
            job.setCity("长沙");
            job.setSalary("8-12K");
            job.setEducation("本科");
            job.setExperience("1-3年");
            job.setStatus("ACTIVE");
            job.setSkillsJson("[]");
            job.setResponsibilitiesJson("[]");
            job.setPublishedAt(base.minusMinutes(index));
            jobs.add(job);
        }
        when(repository.findRecommendationCandidates("长沙")).thenReturn(jobs);

        JobPage page = service.browse("", "长沙", "latest", 5, 24, JobService.JobFilter.empty());

        assertEquals(130, page.total());
        assertEquals(5, page.page());
        assertEquals(24, page.size());
        assertEquals(10, page.items().size());
        assertEquals(130, page.total());
    }
}

