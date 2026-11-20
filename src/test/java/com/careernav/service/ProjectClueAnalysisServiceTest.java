package com.careernav.service;

import com.careernav.domain.ApiModels.ProjectClueAnalysis;
import com.careernav.domain.ApiModels.ProjectClueRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectClueAnalysisServiceTest {
    @Test
    void careerPlatformProducesAgentAndVectorWorkstreams() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        ProjectClueAnalysisService service = new ProjectClueAnalysisService(model);

        ProjectClueAnalysis result = service.analyze(new ProjectClueRequest(
                "CareerNav", "后端与智能体开发", "Spring Boot、PostgreSQL、Qdrant、LangChain4j、LangGraph、Vue",
                "简历诊断、岗位匹配、模拟面试和向量召回", "Agent 编排和检索质量", "完整求职闭环", "Java后端"));

        assertTrue(result.workstreams().contains("Agent 编排与工具调用"));
        assertTrue(result.workstreams().contains("向量检索与召回"));
        assertTrue(result.workstreams().contains("数据库与数据建模"));
        assertTrue(result.workstreams().contains("前端页面与交互"));
        assertFalse(result.workstreams().contains("订单与交易链路"));
    }

    @Test
    void ecommerceProjectProducesTransactionWorkstreams() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.available()).thenReturn(false);
        ProjectClueAnalysisService service = new ProjectClueAnalysisService(model);

        ProjectClueAnalysis result = service.analyze(new ProjectClueRequest(
                "校园二手交易平台", "后端开发", "Spring Boot、MySQL、Redis",
                "商品发布、下单、支付回调和库存扣减", "重复支付和库存一致性", "完成订单闭环", "Java后端"));

        assertTrue(result.workstreams().contains("订单与交易链路"));
        assertTrue(result.workstreams().contains("后端接口开发"));
        assertFalse(result.workstreams().contains("向量检索与召回"));
    }
}