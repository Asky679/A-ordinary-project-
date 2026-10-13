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

@Component
public class LangGraphInterviewClient {
    private static final Logger log = LoggerFactory.getLogger(LangGraphInterviewClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LangGraphInterviewClient(@Value("${app.langgraph.enabled:false}") boolean enabled,
                                    @Value("${app.langgraph.base-url:http://127.0.0.1:8001}") String baseUrl,
                                    ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> configuration() {
        return Map.of("enabled", enabled, "baseUrl", baseUrl, "available", available());
    }

    public boolean available() {
        if (!enabled) return false;
        try {
            JsonNode response = get("/health");
            return response != null && "ok".equalsIgnoreCase(response.path("status").asText(""));
        } catch (Exception exception) {
            log.warn("LangGraph agent unavailable: {}", exception.getMessage());
            return false;
        }
    }

    public AgentTurn start(Long sessionId, ResumeView resume, JobView job, String difficulty) {
        if (!enabled) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", String.valueOf(sessionId));
            body.put("resume", resumeContext(resume));
            body.put("job", jobContext(job));
            body.put("difficulty", difficulty);
            return parse(post("/v1/interviews/start", body));
        } catch (Exception exception) {
            log.warn("LangGraph start failed: {}", exception.getMessage());
            return null;
        }
    }

    public AgentTurn answer(Long sessionId, String answer, ResumeView resume, JobView job) {
        if (!enabled) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("answer", answer);
            body.put("resume", resumeContext(resume));
            body.put("job", jobContext(job));
            return parse(post("/v1/interviews/" + sessionId + "/answer", body));
        } catch (Exception exception) {
            log.warn("LangGraph answer failed: {}", exception.getMessage());
            return null;
        }
    }

    private Map<String, Object> resumeContext(ResumeView resume) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("targetRole", resume.targetRole());
        context.put("city", resume.city());
        context.put("education", resume.education());
        context.put("school", resume.school());
        context.put("major", resume.major());
        context.put("graduationYear", resume.graduationYear());
        context.put("skills", resume.skills());
        context.put("projects", resume.projects());
        context.put("experiences", resume.experiences());
        context.put("selfEvaluation", resume.selfEvaluation());
        return context;
    }

    private Map<String, Object> jobContext(JobView job) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("title", job.title());
        context.put("company", job.company());
        context.put("city", job.city());
        context.put("salary", job.salary());
        context.put("education", job.education());
        context.put("experience", job.experience());
        context.put("skills", job.skills());
        context.put("description", job.description());
        context.put("jobDescription", job.jobDescription());
        context.put("jobRequirements", job.jobRequirements());
        context.put("contentOrigin", job.contentOrigin());
        context.put("source", job.source());
        return context;
    }
    private AgentTurn parse(JsonNode response) {
        if (response == null) return null;
        List<String> tools = new ArrayList<>();
        response.path("toolCalls").forEach(item -> tools.add(item.asText()));
        List<String> memory = new ArrayList<>();
        response.path("memory").forEach(item -> memory.add(item.asText()));
        return new AgentTurn(
                response.path("question").asText(""),
                response.path("score").isNumber() ? response.path("score").asInt() : null,
                response.path("feedback").asText(""),
                response.path("done").asBoolean(false),
                response.path("round").asInt(1),
                response.path("stage").asText(""),
                tools,
                memory
        );
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        return objectMapper.readTree(response.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        return objectMapper.readTree(response.body());
    }

    public record AgentTurn(String question, Integer score, String feedback, boolean done,
                            int round, String stage, List<String> toolCalls, List<String> memory) {}
}