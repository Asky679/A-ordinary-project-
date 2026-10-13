package com.careernav.service;

import com.careernav.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbeddingClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public boolean configured() {
        String key = properties.getEmbedding().getApiKey();
        return key != null && !key.isBlank();
    }

    public float[] embed(String text) {
        if (!configured() || text == null || text.isBlank()) return null;
        try {
            JsonNode response = request(Map.of(
                    "model", properties.getEmbedding().getModel(),
                    "input", text,
                    "dimensions", properties.getEmbedding().getDimensions(),
                    "encoding_format", "float"
            ));
            return parseVector(response.path("data").path(0).path("embedding"));
        } catch (Exception exception) {
            log.warn("Embedding request failed: {}", exception.getMessage());
            return null;
        }
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (!configured() || texts == null || texts.isEmpty()) return List.of();
        try {
            JsonNode response = request(Map.of(
                    "model", properties.getEmbedding().getModel(),
                    "input", texts,
                    "dimensions", properties.getEmbedding().getDimensions(),
                    "encoding_format", "float"
            ));
            JsonNode data = response.path("data");
            if (!data.isArray() || data.size() != texts.size()) return List.of();
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                float[] vector = parseVector(item.path("embedding"));
                if (vector == null) return List.of();
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception exception) {
            log.warn("Embedding batch request failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private JsonNode request(Object body) throws Exception {
        String base = properties.getEmbedding().getBaseUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        String apiKey = properties.getEmbedding().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        String json = objectMapper.writeValueAsString(body);
        builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Embedding HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private float[] parseVector(JsonNode array) {
        if (array == null || !array.isArray()) return null;
        if (array.size() != properties.getEmbedding().getDimensions()) {
            throw new IllegalStateException("Embedding dimension mismatch: " + array.size());
        }
        float[] result = new float[array.size()];
        for (int i = 0; i < array.size(); i++) result[i] = (float) array.get(i).asDouble();
        return result;
    }
}