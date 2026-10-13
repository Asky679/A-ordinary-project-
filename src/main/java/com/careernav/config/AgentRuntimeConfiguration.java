package com.careernav.config;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
@Configuration
public class AgentRuntimeConfiguration {
 @Bean
 @ConditionalOnExpression("'${app.agent.api-key:}' != ''")
 public ChatLanguageModel chatLanguageModel(AppProperties properties){AppProperties.Agent agent=properties.getAgent();return OpenAiChatModel.builder().apiKey(agent.getApiKey()).baseUrl(agent.getBaseUrl()).modelName(agent.getModel()).temperature(agent.getTemperature()).timeout(Duration.ofSeconds(agent.getTimeoutSeconds())).maxRetries(1).build();}
}
