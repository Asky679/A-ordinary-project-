package com.careernav.service;

import com.careernav.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class QdrantClient {
    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

    private final AppProperties properties;
    private final RestClient client;

    public QdrantClient(AppProperties properties) {
        this.properties = properties;
        String baseUrl = properties.getQdrant().getBaseUrl().replaceAll("/+$", "");
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        String apiKey = properties.getQdrant().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("api-key", apiKey);
        this.client = builder.build();
    }

    public String version() {
        try { return root().path("version").asText("unknown"); }
        catch (Exception exception) { log.warn("Qdrant version lookup failed: {}", exception.getMessage()); return "unavailable"; }
    }

    public boolean available() {
        try { return "ok".equalsIgnoreCase(root().path("status").asText("")) || root().path("version").isTextual(); }
        catch (Exception ignored) { return false; }
    }

    public boolean collectionExists() {
        try { return collectionInfo() != null; }
        catch (Exception ignored) { return false; }
    }

    public JsonNode collectionInfo() { return get("/collections/" + collection()).path("result"); }

    public long count() {
        if (!collectionExists()) return 0;
        try { return post("/collections/" + collection() + "/points/count", Map.of("exact", true)).path("result").path("count").asLong(0); }
        catch (Exception exception) { log.warn("Qdrant count failed: {}", exception.getMessage()); return 0; }
    }

    public void ensureCollection(int dimensions) {
        if (collectionExists()) return;
        put("/collections/" + collection(), Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")));
        try {
            put("/collections/" + collection() + "/index?wait=true", Map.of("field_name", "status", "field_schema", "keyword"));
            put("/collections/" + collection() + "/index?wait=true", Map.of("field_name", "dataset_year", "field_schema", "integer"));
        } catch (Exception exception) { log.warn("Qdrant payload index creation skipped: {}", exception.getMessage()); }
    }

    public void upsert(List<Map<String, Object>> points) {
        if (points == null || points.isEmpty()) return;
        put("/collections/" + collection() + "/points?wait=true", Map.of("points", points));
    }

    public JsonNode search(float[] vector, Map<String, Object> filter, int limit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        if (filter != null && !filter.isEmpty()) body.put("filter", filter);
        return post("/collections/" + collection() + "/points/search", body).path("result");
    }

    private JsonNode root() { return get("/"); }
    private String collection() { return properties.getQdrant().getCollection(); }
    private JsonNode get(String path) { return client.get().uri(path).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class); }
    private JsonNode post(String path, Object body) { return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class); }
    private JsonNode put(String path, Object body) { return client.put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class); }
}