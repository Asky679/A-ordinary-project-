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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用 Python 侧的「简历 -> 岗位推荐」Agent。
 *
 * <p>该 Agent 会自主规划检索、必要时重试，并把每个岗位的匹配分交回 Java 侧的加权算法计算，
 * 因此这里的返回体是 Agent 的完整报告（含推荐列表、技能缺口和决策轨迹），不做二次解析。
 */
@Component
public class LangGraphJobAgentClient {
    private static final Logger log = LoggerFactory.getLogger(LangGraphJobAgentClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LangGraphJobAgentClient(@Value("${app.langgraph.enabled:false}") boolean enabled,
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

    public boolean enabled() {
        return enabled;
    }

    public JsonNode recommend(Long resumeId, String city, String excludedCities, int limit) {
        if (!enabled || resumeId == null) {
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resumeId", resumeId);
            body.put("city", city == null ? "" : city);
            body.put("excludedCities", excludedCities == null ? "" : excludedCities);
            body.put("limit", limit <= 0 ? 8 : limit);
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/diagnosis/agent"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                log.warn("LangGraph job agent returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception exception) {
            log.warn("LangGraph job agent call failed: {}", exception.getMessage());
            return null;
        }
    }
}
