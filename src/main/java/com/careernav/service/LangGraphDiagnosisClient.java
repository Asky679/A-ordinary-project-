package com.careernav.service;

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

/** 调用 Python 侧「简历诊断 Agent」，由它自主选择工具补充证据。 */
@Component
public class LangGraphDiagnosisClient {
    private static final Logger log = LoggerFactory.getLogger(LangGraphDiagnosisClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LangGraphDiagnosisClient(@Value("${app.langgraph.enabled:false}") boolean enabled,
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

    public boolean available() {
        if (!enabled) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) return false;
            return "ok".equalsIgnoreCase(objectMapper.readTree(response.body()).path("status").asText(""));
        } catch (Exception exception) {
            log.warn("LangGraph diagnosis agent unavailable: {}", exception.getMessage());
            return false;
        }
    }

    public Enrichment analyze(Long resumeId, Long jobId) {
        if (!enabled || resumeId == null) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resumeId", resumeId);
            if (jobId != null) body.put("jobId", jobId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/diagnosis/analyze"))
                    .timeout(Duration.ofSeconds(150))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                log.warn("Diagnosis agent returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("available").asBoolean(false)) return null;

            List<String> strengths = new ArrayList<>();
            root.path("strengths").forEach(node -> strengths.add(node.asText()));

            List<Suggestion> suggestions = new ArrayList<>();
            root.path("suggestions").forEach(node -> suggestions.add(new Suggestion(
                    node.path("priority").asText("P2"),
                    node.path("category").asText("Agent 建议"),
                    node.path("issue").asText(""),
                    node.path("action").asText(""),
                    node.path("example").asText(""))));

            List<String> focus = new ArrayList<>();
            root.path("learningFocus").forEach(node -> focus.add(node.asText()));

            List<String> toolCalls = new ArrayList<>();
            root.path("toolCalls").forEach(node -> toolCalls.add(node.asText()));

            return new Enrichment(root.path("summary").asText(""), strengths, suggestions, focus,
                    root.path("reasoningSummary").asText(""), toolCalls);
        } catch (Exception exception) {
            log.warn("Diagnosis agent call failed: {}", exception.getMessage());
            return null;
        }
    }

    public record Suggestion(String priority, String category, String issue, String action, String example) {}

    public record Enrichment(String summary, List<String> strengths, List<Suggestion> suggestions,
                             List<String> learningFocus, String reasoningSummary, List<String> toolCalls) {}
}
