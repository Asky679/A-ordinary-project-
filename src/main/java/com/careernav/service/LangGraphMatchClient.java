package com.careernav.service;

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

/** 调用 Python 侧岗位匹配 Agent（按岗位自身要求打分）。 */
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
                        node.path("score").asInt(0),
                        node.path("verdict").asText(""),
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
        context.put("skills", resume.skills());
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

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Breakdown(String requirement, String importance, String status, String evidence) {}

    public record AgentScore(Long jobId, int score, String verdict, String reason, List<Breakdown> breakdown) {}

    public record RerankResult(boolean available, List<AgentScore> items) {}

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("mode", enabled ? "LANGGRAPH_LLM" : "DISABLED");
        status.put("maxJobsPerCall", 20);
        status.put("fusedScore", "agent 70% + rule 30%");
        return status;
    }
}
