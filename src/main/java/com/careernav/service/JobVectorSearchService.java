package com.careernav.service;

import com.careernav.config.AppProperties;
import com.careernav.domain.ApiModels.JobView;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class JobVectorSearchService {
    private static final Logger log = LoggerFactory.getLogger(JobVectorSearchService.class);
    private static final int UPSERT_BATCH_SIZE = 64;

    private final JdbcTemplate jdbc;
    private final JsonCodec jsonCodec;
    private final EmbeddingClient embeddingClient;
    private final AppProperties properties;
    private final QdrantClient qdrant;

    public JobVectorSearchService(JdbcTemplate jdbc, JsonCodec jsonCodec, EmbeddingClient embeddingClient,
                                  AppProperties properties, QdrantClient qdrant) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.qdrant = qdrant;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        long totalJobs = Objects.requireNonNullElse(jdbc.queryForObject("SELECT count(*) FROM jobs", Long.class), 0L);
        long indexedJobs = qdrant.count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("database", "PostgreSQL");
        status.put("vectorDatabase", "Qdrant");
        status.put("qdrantUrl", properties.getQdrant().getBaseUrl());
        status.put("qdrantVersion", qdrant.version());
        status.put("collection", properties.getQdrant().getCollection());
        status.put("collectionExists", qdrant.collectionExists());
        status.put("vectorDimensions", properties.getEmbedding().getDimensions());
        status.put("totalJobs", totalJobs);
        status.put("indexedJobs", indexedJobs);
        status.put("embeddingConfigured", embeddingClient.configured());
        status.put("mode", indexedJobs > 0 ? "QDRANT_READY" : "QDRANT_EMPTY");
        return status;
    }

    @Transactional(readOnly = true)
    public List<JobView> search(String query, String city, Integer year, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String q = query == null ? "" : query.trim();
        String cityFilter = city == null ? "" : city.trim();
        int yearFilter = year == null ? 0 : year;

        if (!q.isBlank() && embeddingClient.configured() && qdrant.collectionExists()) {
            float[] vector = embeddingClient.embed(q);
            if (vector != null) {
                // status / city / dataset_year are pushed down into Qdrant, so the hits
                // already satisfy every filter. That removes the old post-filtering and
                // the 4x over-fetch it needed to compensate for dropped rows.
                JsonNode result = qdrant.search(vector, buildFilter(cityFilter, yearFilter), safeLimit);
                List<JobView> jobs = loadJobs(result);
                if (!jobs.isEmpty()) return jobs;
            }
        }
        return keywordSearch(q, cityFilter, yearFilter, safeLimit);
    }

    @Transactional
    public int reindex(int limit) {
        if (!embeddingClient.configured()) return 0;
        // 岗位总量已超过 9,000，上限从 5,000 放宽到 20,000，
        // 否则一次重建只能覆盖前 5,000 条，后续导入的岗位进不了向量库。
        int safeLimit = Math.max(1, Math.min(limit, 20000));
        int dimensions = properties.getEmbedding().getDimensions();
        qdrant.ensureCollection(dimensions);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, title, company, city, salary, education, experience, skills_json, description,
                       job_description, job_requirements, source, source_url, collected_at, published_at,
                       valid_through, last_seen_at, status, dataset_year, search_text
                FROM jobs
                WHERE status = 'ACTIVE'
                ORDER BY id
                LIMIT ?
                """, safeLimit);
        int updated = 0;
        for (int start = 0; start < rows.size(); start += UPSERT_BATCH_SIZE) {
            List<Map<String, Object>> batch = rows.subList(start, Math.min(rows.size(), start + UPSERT_BATCH_SIZE));
            List<String> texts = batch.stream().map(this::searchText).toList();
            List<float[]> vectors = embeddingClient.embedBatch(texts);
            if (vectors.size() != batch.size()) {
                vectors = texts.stream().map(embeddingClient::embed).toList();
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                float[] vector = vectors.size() > index ? vectors.get(index) : null;
                if (vector == null) continue;
                Map<String, Object> row = batch.get(index);
                long id = ((Number) row.get("id")).longValue();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("job_id", id);
                put(payload, "title", row.get("title"));
                put(payload, "company", row.get("company"));
                put(payload, "city", row.get("city"));
                put(payload, "status", row.get("status"));
                put(payload, "dataset_year", row.get("dataset_year"));
                put(payload, "source", row.get("source"));
                points.add(Map.of("id", id, "vector", vector, "payload", payload));
            }
            qdrant.upsert(points);
            updated += points.size();
        }
        return updated;
    }

    private Map<String, Object> buildFilter(String cityFilter, int yearFilter) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "status", "match", Map.of("value", "ACTIVE")));
        if (cityFilter != null && !cityFilter.isBlank()) {
            // The city column is normalised (243 distinct values, no suffix or district
            // text), and the UI passes a value taken from that same set, so an exact
            // keyword match is both correct and cheaper than a contains() post-filter.
            must.add(Map.of("key", "city", "match", Map.of("value", cityFilter.trim())));
        }
        if (yearFilter > 0) must.add(Map.of("key", "dataset_year", "match", Map.of("value", yearFilter)));
        return Map.of("must", must);
    }

    private List<JobView> loadJobs(JsonNode result) {
        if (result == null || !result.isArray() || result.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : result) {
            long id = item.path("payload").path("job_id").asLong(0);
            if (id > 0) ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT id, external_id, title, company, city, salary, education, experience,
                       skills_json, responsibilities_json, description, job_description, job_requirements,
                       content_origin, ai_model, ai_confidence, source, source_url, collected_at,
                       published_at, valid_through, last_seen_at, status,
                       category, industry, company_size, location
                FROM jobs
                WHERE id IN (%s)
                """.formatted(placeholders);
        Map<Long, JobView> byId = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { JobView view = mapJob(rs, 0); byId.put(view.id(), view); }, ids.toArray());
        List<JobView> ordered = new ArrayList<>();
        for (Long id : ids) {
            JobView view = byId.get(id);
            if (view != null) ordered.add(view);
        }
        return ordered;
    }

    private List<JobView> keywordSearch(String query, String city, int year, int limit) {
        if (query.isBlank()) {
            return jdbc.query(recentSql(), this::mapJob, city, city, year, year, limit);
        }
        return jdbc.query(keywordSql(), this::mapJob, query, query, query,
                city, city, year, year, query, query, query, limit);
    }

    private String searchText(Map<String, Object> row) {
        String existing = Objects.toString(row.get("search_text"), "").trim();
        if (!existing.isBlank()) return existing;
        return String.join(" ", Objects.toString(row.get("title"), ""), Objects.toString(row.get("company"), ""),
                Objects.toString(row.get("city"), ""), Objects.toString(row.get("skills_json"), ""),
                Objects.toString(row.get("description"), ""), Objects.toString(row.get("job_description"), ""),
                Objects.toString(row.get("job_requirements"), ""));
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }



    private String keywordSql() {
        return """
                SELECT id, external_id, title, company, city, salary, education, experience,
                       skills_json, responsibilities_json, description, job_description, job_requirements,
                       content_origin, ai_model, ai_confidence, source, source_url,
                       collected_at, published_at, valid_through, last_seen_at, status
                FROM jobs
                WHERE status = 'ACTIVE'
                  AND (title ILIKE CONCAT('%', ?, '%')
                       OR company ILIKE CONCAT('%', ?, '%')
                       OR COALESCE(search_text, '') ILIKE CONCAT('%', ?, '%'))
                  AND (? = '' OR city ILIKE CONCAT('%', ?, '%'))
                  AND (? = 0 OR dataset_year = ?)
                ORDER BY similarity(title, ?) + similarity(company, ?) + similarity(COALESCE(search_text, ''), ?) DESC,
                         dataset_year DESC NULLS LAST
                LIMIT ?
                """;
    }

    private String recentSql() {
        return """
                SELECT id, external_id, title, company, city, salary, education, experience,
                       skills_json, responsibilities_json, description, job_description, job_requirements,
                       content_origin, ai_model, ai_confidence, source, source_url,
                       collected_at, published_at, valid_through, last_seen_at, status
                FROM jobs
                WHERE status = 'ACTIVE'
                  AND (? = '' OR city ILIKE CONCAT('%', ?, '%'))
                  AND (? = 0 OR dataset_year = ?)
                ORDER BY dataset_year DESC NULLS LAST, id DESC
                LIMIT ?
                """;
    }

    private JobView mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new JobView(
                rs.getLong("id"), rs.getString("external_id"), rs.getString("title"), rs.getString("company"),
                rs.getString("city"), rs.getString("salary"), rs.getString("education"), rs.getString("experience"),
                jsonCodec.readList(rs.getString("skills_json"), String.class),
                jsonCodec.readList(rs.getString("responsibilities_json"), String.class),
                rs.getString("description"), rs.getString("job_description"), rs.getString("job_requirements"),
                rs.getString("content_origin"), rs.getString("ai_model"), rs.getString("ai_confidence"),
                rs.getString("source"), rs.getString("source_url"),
                date(rs.getTimestamp("collected_at")), date(rs.getTimestamp("published_at")),
                date(rs.getTimestamp("valid_through")), date(rs.getTimestamp("last_seen_at")),
                rs.getString("status"),
                rs.getString("category"), rs.getString("industry"),
                rs.getString("company_size"), rs.getString("location")
        );
    }

    private LocalDateTime date(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}