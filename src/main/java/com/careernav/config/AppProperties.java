package com.careernav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Agent agent = new Agent();
    private final Mcp mcp = new Mcp();
    private final Collection collection = new Collection();
    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();

    public Agent getAgent() { return agent; }
    public Mcp getMcp() { return mcp; }
    public Collection getCollection() { return collection; }
    public Embedding getEmbedding() { return embedding; }
    public Qdrant getQdrant() { return qdrant; }

    public static class Agent {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        private Double temperature = 0.2;
        private Integer timeoutSeconds = 45;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Mcp {
        private boolean enabled;
        private String endpoint = "http://localhost:3000/mcp";
        private String toolName = "search_jobs";
        private String token;
        private long refreshSeconds = 900;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public long getRefreshSeconds() { return refreshSeconds; }
        public void setRefreshSeconds(long refreshSeconds) { this.refreshSeconds = refreshSeconds; }
    }

    public static class Collection {
        private String expiryCron = "0 10 3 * * *";
        private String incrementalCron = "0 20 3 * * MON";
        private String fullCron = "0 20 3 1 * *";
        private String statsCron = "0 30 4 1 * *";
        private int staleDays = 45;
        private int expireDays = 60;
        private long defaultRequestIntervalMs = 3000;
        private long maxRequestIntervalMs = 5000;
        private String userAgent = "CareerNavDataBot/1.0";
        private String contactEmail = "data-team@example.edu";
        public String getExpiryCron() { return expiryCron; } public void setExpiryCron(String v) { expiryCron = v; }
        public String getIncrementalCron() { return incrementalCron; } public void setIncrementalCron(String v) { incrementalCron = v; }
        public String getFullCron() { return fullCron; } public void setFullCron(String v) { fullCron = v; }
        public String getStatsCron() { return statsCron; } public void setStatsCron(String v) { statsCron = v; }
        public int getStaleDays() { return staleDays; } public void setStaleDays(int v) { staleDays = v; }
        public int getExpireDays() { return expireDays; } public void setExpireDays(int v) { expireDays = v; }
        public long getDefaultRequestIntervalMs() { return defaultRequestIntervalMs; } public void setDefaultRequestIntervalMs(long v) { defaultRequestIntervalMs = v; }
        public long getMaxRequestIntervalMs() { return maxRequestIntervalMs; } public void setMaxRequestIntervalMs(long v) { maxRequestIntervalMs = v; }
        public String getUserAgent() { return userAgent; } public void setUserAgent(String v) { userAgent = v; }
        public String getContactEmail() { return contactEmail; } public void setContactEmail(String v) { contactEmail = v; }
    }


    public static class Qdrant {
        private String baseUrl = "http://127.0.0.1:6333";
        private String apiKey;
        private String collection = "careernav_jobs";
        private Integer timeoutSeconds = 20;
        public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
        public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey = v; }
        public String getCollection() { return collection; } public void setCollection(String v) { collection = v; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(Integer v) { timeoutSeconds = v; }
    }
    public static class Embedding {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "text-embedding-3-small";
        private int dimensions = 256;
        public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey = v; }
        public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
        public String getModel() { return model; } public void setModel(String v) { model = v; }
        public int getDimensions() { return dimensions; } public void setDimensions(int v) { dimensions = v; }
    }
}
