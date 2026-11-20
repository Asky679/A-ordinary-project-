package com.careernav.service;

import com.careernav.domain.ApiModels.ProjectClueAnalysis;
import com.careernav.domain.ApiModels.ProjectClueRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 根据项目事实生成动态工作方面，避免所有项目都显示同一组固定选项。 */
@Service
public class ProjectClueAnalysisService {
    private static final int MAX_WORKSTREAMS = 10;
    private final AgentModelClient model;

    public ProjectClueAnalysisService(AgentModelClient model) {
        this.model = model;
    }

    public ProjectClueAnalysis analyze(ProjectClueRequest request) {
        String name = safe(request == null ? null : request.projectName(), "当前项目");
        String text = projectText(request);
        List<String> ruleItems = ruleWorkstreams(text);
        if (model != null && model.available()) {
            String prompt = """
                    你是项目工作方面分析器。请只根据下面的项目事实，总结这个项目可能涉及的工作方面。
                    这些只是供候选人勾选“是否由自己负责”的候选方面，不代表候选人实际做过，禁止编造项目事实。
                    返回 5 到 10 个中文短标签，每个不超过 18 字。
                    项目名称：%s
                    目标岗位：%s
                    项目描述：%s
                    技术栈：%s
                    项目难点：%s
                    项目亮点：%s
                    返回 JSON：{"summary":"一句话说明这些只是候选方面，不能当作已确认事实","workstreams":["候选方面1","候选方面2"]}
                    """.formatted(name, safe(request.targetRole(), "未提供"), safe(request.description(), "未提供"),
                    safe(request.techStack(), "未提供"), safe(request.challenge(), "未提供"),
                    safe(request.highlight(), "未提供"));
            Draft draft = model.generateJson(prompt, Draft.class);
            List<String> generated = clean(draft == null ? null : draft.workstreams());
            if (generated.size() >= 5) {
                return new ProjectClueAnalysis(name,
                        safe(draft.summary(), "以下方面由项目描述和技术栈归纳，仅作为勾选参考，不代表候选人已经做过。"),
                        generated, "LLM");
            }
        }
        return new ProjectClueAnalysis(name,
                "根据项目描述和技术栈归纳以下可能涉及的工作方面，仅作为勾选参考，不代表候选人已经做过。",
                ruleItems, "RULE");
    }

    private List<String> ruleWorkstreams(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> items = new LinkedHashSet<>();
        addIf(items, lower, "Agent 编排与工具调用", "agent", "langgraph", "langchain", "智能体", "提示词", "llm");
        addIf(items, lower, "向量检索与召回", "qdrant", "向量", "embedding", "召回", "检索");
        addIf(items, lower, "数据库与数据建模", "postgresql", "mysql", "数据库", "数据表", "索引", "数据模型");
        addIf(items, lower, "后端接口开发", "spring boot", "java", "后端", "接口", "api", "服务端");
        addIf(items, lower, "前端页面与交互", "vue", "react", "前端", "页面", "javascript", "原生 js");
        addIf(items, lower, "业务规则与算法设计", "简历", "岗位", "诊断", "匹配", "推荐", "分数", "算法");
        addIf(items, lower, "鉴权与权限控制", "jwt", "登录", "鉴权", "权限");
        addIf(items, lower, "订单与交易链路", "订单", "支付", "库存", "商品", "交易");
        addIf(items, lower, "测试验收与质量保障", "测试", "junit", "验收", "质量", "自动化");
        addIf(items, lower, "部署与运维", "docker", "nginx", "linux", "部署", "运维", "ci/cd");
        addIf(items, lower, "日志监控与故障处理", "日志", "监控", "告警", "故障", "异常");
        addIf(items, lower, "需求分析与协作", "需求", "产品", "协作", "沟通", "用户");
        for (String generic : List.of("核心业务开发", "数据存储与接口", "联调与问题排查", "测试验收", "部署与运维", "文档与交付")) {
            if (items.size() >= 6) break;
            items.add(generic);
        }
        return items.stream().limit(MAX_WORKSTREAMS).toList();
    }

    private void addIf(LinkedHashSet<String> items, String text, String label, String... terms) {
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                items.add(label);
                return;
            }
        }
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String text = safe(value, "").trim().replaceAll("\\s+", " ");
            if (text.isBlank()) continue;
            result.add(text.length() > 24 ? text.substring(0, 24) : text);
            if (result.size() >= MAX_WORKSTREAMS) break;
        }
        return new ArrayList<>(result);
    }

    private String projectText(ProjectClueRequest request) {
        if (request == null) return "";
        return String.join(" ",
                safe(request.projectName(), ""), safe(request.role(), ""),
                safe(request.techStack(), ""), safe(request.description(), ""),
                safe(request.challenge(), ""), safe(request.highlight(), ""));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record Draft(String summary, List<String> workstreams) {}
}