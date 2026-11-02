package com.careernav.service;

import com.careernav.domain.ApiModels.AgentMatchWeights;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.ApiModels.ResumeView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 Python 侧岗位匹配 Agent，获取受限权重与逐条证据，不接收 LLM 直接分数。 */
@Component
public class LangGraphMatchClient {
    private static final Logger log = LoggerFactory.getLogger(LangGraphMatchClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LangGraphMatchClient(@Value("${app.langgraph.enabled:false}") boolean enabled,
                                @Value("${app.langgraph.base-url:http://127.0.0.1:8001}") String baseUrl,
                                ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public RerankResult rerank(ResumeView resume, List<JobView> jobs) {
        if (!enabled || resume == null || jobs == null || jobs.isEmpty()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resume", resumeContext(resume));
            body.put("jobs", jobs.stream().map(this::jobContext).toList());

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/match/rerank"))
                    .timeout(Duration.ofSeconds(150))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                log.warn("Match agent returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            boolean available = root.path("available").asBoolean(false);
            List<AgentScore> items = new ArrayList<>();
            root.path("items").forEach(node -> {
                List<Breakdown> breakdown = new ArrayList<>();
                node.path("breakdown").forEach(row -> breakdown.add(new Breakdown(
                        row.path("requirement").asText(""),
                        row.path("importance").asText(""),
                        row.path("status").asText(""),
                        row.path("evidence").asText(""))));
                items.add(new AgentScore(
                        node.path("jobId").asLong(0),
                        readWeights(node.path("weights")),
                        node.path("reason").asText(""),
                        breakdown));
            });
            return new RerankResult(available, items);
        } catch (Exception exception) {
            log.warn("Match agent call failed: {}", exception.getMessage());
            return null;
        }
    }

    private Map<String, Object> resumeContext(ResumeView resume) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("targetRole", resume.targetRole());
        context.put("city", resume.city());
        context.put("education", resume.education());
        context.put("educationVerified", resume.educationVerified());
        context.put("educationVerificationStatus", resume.educationVerificationStatus());
        context.put("skills", resume.skills());
        context.put("skillProficiencies", resume.skillProficiencies());
        context.put("projects", resume.projects());
        context.put("experiences", resume.experiences());
        context.put("selfEvaluation", resume.selfEvaluation());
        return context;
    }

    private Map<String, Object> jobContext(JobView job) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("jobId", job.id());
        context.put("title", job.title());
        context.put("company", job.company());
        context.put("industry", job.industry());
        context.put("companySize", job.companySize());
        context.put("salary", job.salary());
        context.put("category", job.category());
        context.put("location", job.location());
        context.put("education", job.education());
        context.put("experience", job.experience());
        context.put("skills", job.skills());
        String description = job.jobDescription();
        if (description == null || description.isBlank()) description = job.description();
        context.put("jobDescription", description == null ? "" : clip(description, 700));
        String requirements = job.jobRequirements();
        context.put("jobRequirements", requirements == null ? "" : clip(requirements, 1100));
        return context;
    }

    private AgentMatchWeights readWeights(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        List<String> keys = List.of("skills", "role", "experience", "education", "city", "evidence");
        if (keys.stream().anyMatch(key -> !node.has(key))) return null;
        AgentMatchWeights weights = new AgentMatchWeights(
                node.path("skills").asInt(0), node.path("role").asInt(0),
                node.path("experience").asInt(0), node.path("education").asInt(0),
                node.path("city").asInt(0), node.path("evidence").asInt(0));
        int total = weights.skills() + weights.role() + weights.experience()
                + weights.education() + weights.city() + weights.evidence();
        return total > 0 ? weights : null;
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Breakdown(String requirement, String importance, String status, String evidence) {}

    public record AgentScore(Long jobId, AgentMatchWeights weights, String reason, List<Breakdown> breakdown) {}

    public record RerankResult(boolean available, List<AgentScore> items) {}

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("mode", enabled ? "LANGGRAPH_WEIGHTED_RULE" : "DISABLED");
        status.put("maxJobsPerCall", 20);
        status.put("scoreMode", "agent bounded weights + deterministic rule scoring");
        status.put("llmDirectScore", false);
        return status;
    }
}
