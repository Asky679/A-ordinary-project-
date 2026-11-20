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

    @Test
    void removesGenericAdministrativeDutiesFromInterviewQuestions() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        JobInterviewBlueprintService service = new JobInterviewBlueprintService(model);
        JobView job = new JobView(
                11L, "job-11", "Java开发", "测试公司", "长沙", "8-12K", "不限", "经验不限",
                List.of("Java"), List.of("完成上级安排的工作", "负责和前端完成App接口开发"),
                "负责后端Java开发和接口联调", "完成领导交办的其他工作",
                "熟悉 Java 和 Spring Boot", "ORIGINAL", null, null,
                "测试来源", null, null, null, null, null, "ACTIVE", "Java后端", "互联网",
                "20-99人", "长沙");

        JobInterviewBlueprint blueprint = service.build(job, null);

        assertTrue(blueprint.coreResponsibilities().stream().noneMatch(value -> value.contains("上级")));
        assertTrue(blueprint.coreResponsibilities().stream().noneMatch(value -> value.contains("领导")));
        assertTrue(blueprint.coreResponsibilities().stream().anyMatch(value -> value.contains("后端 Java") || value.contains("接口")));
    }
}
