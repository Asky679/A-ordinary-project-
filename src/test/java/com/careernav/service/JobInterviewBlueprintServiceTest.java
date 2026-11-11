package com.careernav.service;

import com.careernav.domain.ApiModels.JobInterviewBlueprint;
import com.careernav.domain.ApiModels.JobView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobInterviewBlueprintServiceTest {
    @Test
    void extractsInterviewBlueprintFromExistingJobDataWithoutModel() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        JobInterviewBlueprintService service = new JobInterviewBlueprintService(model);
        JobView job = new JobView(
                10L, "job-10", "Java软件工程师", "测试公司", "长沙", "10-15K", "本科", "1-3年",
                List.of(), List.of("负责订单接口开发"), "负责 Java、Redis 和接口测试",
                "熟悉 Java、Spring Boot、MySQL、Redis，具有功能测试经验",
                "Java、Spring Boot、MySQL、Redis、功能测试", "ORIGINAL", null, null,
                "测试来源", null, null, null, null, null, "ACTIVE", "Java后端", "企业软件",
                "100-499人", "长沙");

        JobInterviewBlueprint blueprint = service.build(job, null);

        assertTrue(blueprint.mustHaveSkills().contains("Java"));
        assertTrue(blueprint.mustHaveSkills().contains("Redis"));
        assertFalse(blueprint.coreResponsibilities().isEmpty());
        assertFalse(blueprint.interviewScenarios().isEmpty());
        assertTrue(blueprint.hardConstraints().stream().anyMatch(value -> value.contains("本科")));
    }
}
