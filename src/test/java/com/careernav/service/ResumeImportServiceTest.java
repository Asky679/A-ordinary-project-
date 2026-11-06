package com.careernav.service;

import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeImportResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeImportServiceTest {
    @Test
    void parsesComputerStudentResumeWithoutInventingFields() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        ResumeImportService service = new ResumeImportService(model);
        String text = """
                张三
                求职意向：Java 后端开发
                电话：13800138000 邮箱：zhangsan@example.com
                现居：长沙
                教育背景
                长沙理工大学 计算机科学与技术 本科 预计 2026 年毕业
                专业技能
                Java、Spring Boot、MySQL、Redis、Docker
                项目经历
                校园二手交易平台
                负责订单接口与 Redis 缓存，接口平均响应时间从 800ms 降到 220ms。
                实习经历
                某科技有限公司 Java 开发实习生
                2025.03 - 2025.08
                参与后端接口开发和联调。
                自我评价
                学习能力强，重视工程质量。
                """;
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        ResumeImportResult result = service.importResume(file, "", "TEXT");

        assertEquals("张三", result.ownerName());
        assertEquals("Java 后端开发", result.targetRole());
        assertEquals("长沙", result.city());
        assertEquals("13800138000", result.phone());
        assertEquals("zhangsan@example.com", result.email());
        assertEquals("本科", result.education());
        assertEquals("长沙理工大学", result.school());
        assertTrue(result.skills().contains("Java"));
        assertTrue(result.skills().contains("Spring Boot"));
        assertFalse(result.projects().isEmpty());
        assertFalse(result.experiences().isEmpty());
        assertTrue(result.rawText().contains("校园二手交易平台"));
    }

    @Test
    void keepsAllProjectsAndExperiencesWhenResumeHasMultipleEntries() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        ResumeImportService service = new ResumeImportService(model);
        String text = """
                李四
                求职意向：Java 后端开发
                现居：长沙
                教育背景
                长沙理工大学 软件工程 本科 2027 届
                专业技能
                Java、Spring Boot、PostgreSQL、Qdrant
                项目经历
                职航 CareerNav 智能职业导航平台 | 后端开发负责人 | 2025.01 - 2025.06
                技术栈：Java、Spring Boot、PostgreSQL、Qdrant
                负责岗位检索接口、简历诊断流程和 Agent 调用链设计。
                难点：处理岗位城市过滤与向量召回的一致性问题。
                成果：完成 20 个接口，平均响应时间 180ms。
                校园二手交易平台 | 全栈开发
                技术栈：Spring Boot、MySQL、Vue
                负责商品发布、下单和订单状态流转。
                成果：支持 3000+ 用户同时在线。
                实习 / 工作经历
                长沙众元网络技术有限公司 | Java 开发实习生 | 2025.03 - 2025.08
                参与后端接口开发与联调，负责功能模块编码和自测。
                成果：负责接口平均响应时间 200ms。
                湖南某科技有限公司 | 后端开发实习生 | 2024.07 - 2024.09
                参与权限模块开发和数据库索引优化。
                成果：慢查询数量降低 40%。
                自我评价
                重视工程质量，能够持续定位和解决问题。
                """;
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        ResumeImportResult result = service.importResume(file, "", "TEXT");

        assertEquals(2, result.projects().size());
        assertEquals(2, result.experiences().size());
        assertTrue(result.projects().stream().anyMatch(item -> item.name().contains("CareerNav")));
        assertTrue(result.projects().stream().anyMatch(item -> item.name().contains("校园二手交易平台")));
        assertTrue(result.projects().stream().anyMatch(item -> item.challenge().contains("城市过滤")));
        assertTrue(result.projects().stream().anyMatch(item -> item.description().contains("岗位检索")));
        assertTrue(result.projects().stream().anyMatch(item -> item.techStack().contains("Spring")));
        assertTrue(result.experiences().stream().anyMatch(item ->
                item.company().contains("长沙众元") && item.position().contains("Java")));
        assertTrue(result.experiences().stream().anyMatch(item ->
                item.company().contains("湖南某科技") && item.period().contains("2024.07")));
    }
    @Test
    void partialModelResultDoesNotRemoveRuleParsedEntries() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(true);
        when(model.generateJson(anyString(), eq(ResumeImportService.ModelDraft.class))).thenReturn(
                new ResumeImportService.ModelDraft(
                        "王五", "Java 后端开发", "长沙", "", "", "本科", "长沙理工大学", "软件工程", 2027,
                        List.of("Java"),
                        List.of(new ProjectItem("项目A", "后端开发", "", "AI 补充描述", "", "")),
                        List.of(new ExperienceItem("公司A", "Java 开发实习生", "2024.01-2024.06", "AI 补充描述", "")),
                        ""));
        ResumeImportService service = new ResumeImportService(model);
        String text = """
                王五
                求职意向：Java 后端开发
                项目经历
                项目A | 后端开发
                技术栈：Java
                负责接口开发。
                项目B | 前端开发
                技术栈：Vue
                负责页面开发。
                实习经历
                公司A | Java 开发实习生 | 2024.01 - 2024.06
                参与后端接口开发。
                公司B | 前端开发实习生 | 2023.07 - 2023.09
                参与管理端页面开发。
                """;
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        ResumeImportResult result = service.importResume(file, "", "TEXT");

        assertEquals(2, result.projects().size());
        assertEquals(2, result.experiences().size());
        assertTrue(result.projects().stream().anyMatch(item -> item.name().contains("项目B")));
        assertTrue(result.experiences().stream().anyMatch(item -> item.company().contains("公司B")));
    }
    @Test
    void imageImportRequiresBrowserOcr() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        ResumeImportService service = new ResumeImportService(model);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", new byte[]{1, 2, 3});
        try {
            service.importResume(file, "", "OCR_IMAGE");
        } catch (com.careernav.common.BusinessException exception) {
            assertTrue(exception.getMessage().contains("OCR"));
            return;
        }
        throw new AssertionError("image without OCR text should fail");
    }
}
