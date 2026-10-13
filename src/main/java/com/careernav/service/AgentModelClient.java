package com.careernav.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AgentModelClient {
    private static final Logger log = LoggerFactory.getLogger(AgentModelClient.class);

    private final ObjectProvider<ChatLanguageModel> models;
    private final JsonCodec jsonCodec;

    public AgentModelClient(ObjectProvider<ChatLanguageModel> models, JsonCodec jsonCodec) {
        this.models = models;
        this.jsonCodec = jsonCodec;
    }

    public boolean available() {
        return models.getIfAvailable() != null;
    }

    public String generate(String prompt) {
        ChatLanguageModel model = models.getIfAvailable();
        if (model == null) {
            return null;
        }
        try {
            String result = model.generate(prompt);
            return result == null || result.isBlank() ? null : result.trim();
        } catch (Exception exception) {
            log.warn("Agent model call failed: {}", exception.getMessage());
            return null;
        }
    }

    public <T> T generateJson(String prompt, Class<T> type) {
        String raw = generate(prompt + "\n\n只返回合法 JSON，不要使用 Markdown 代码块，不要添加 JSON 之外的解释。");
        if (raw == null) {
            return null;
        }
        String json = extractJson(raw);
        if (json == null) {
            return null;
        }
        try {
            return jsonCodec.read(json, type);
        } catch (Exception exception) {
            log.warn("Agent JSON response could not be parsed: {}", exception.getMessage());
            return null;
        }
    }

    private String extractJson(String raw) {
        int objectStart = raw.indexOf('{');
        int objectEnd = raw.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return raw.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = raw.indexOf('[');
        int arrayEnd = raw.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return raw.substring(arrayStart, arrayEnd + 1);
        }
        return null;
    }
}
