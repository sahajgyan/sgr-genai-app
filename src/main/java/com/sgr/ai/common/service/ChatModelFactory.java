package com.sgr.ai.common.service;

import com.sgr.ai.common.config.AgentConfig; // Assuming this is your config class
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatModelFactory {

    // --- Inject Global Keys from application.yml ---
    @Value("${langchain4j.open-ai.api-key:}")
    private String defaultOpenAiKey;

    @Value("${langchain4j.google-ai.api-key:}")
    private String defaultGeminiKey;

    @Value("${langchain4j.anthropic.api-key:}")
    private String defaultAnthropicKey;

    @Value("${langchain4j.ollama.base-url:http://localhost:11434}")
    private String defaultOllamaUrl;

    // Inject Azure Defaults
    @Value("${langchain4j.azure-open-ai.endpoint:}")
    private String defaultAzureEndpoint;

    @Value("${langchain4j.azure-open-ai.api-key:}")
    private String defaultAzureKey;

    // Cache: provider:modelName:temperature -> Model Instance
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public ChatModel getModel(AgentConfig.ModelConfig config) {
        // Create cache key
        String cacheKey = String.format("%s:%s:%s",
                config.provider(), config.name(), config.temperature());

        return modelCache.computeIfAbsent(cacheKey, k -> buildModel(config));
    }

    private ChatModel buildModel(AgentConfig.ModelConfig config) {
        String provider = config.provider().toLowerCase();

        switch (provider) {
            // 1. OpenAI (GPT-4, GPT-3.5)
            case "openai":
                return OpenAiChatModel.builder()
                        .apiKey(resolveKey(config, defaultOpenAiKey))
                        .modelName(config.name())
                        .temperature(config.temperature())
                        .timeout(Duration.ofSeconds(60))
                        .build();

            // 2. Google Gemini (Gemini 1.5 Pro/Flash)
            case "gemini":
            case "google":
                return GoogleAiGeminiChatModel.builder()
                        .apiKey(resolveKey(config, defaultGeminiKey))
                        .modelName(config.name()) // e.g., "gemini-1.5-flash"
                        .temperature(config.temperature())
                        .build();

            // 3. Anthropic (Claude 3.5 Sonnet/Haiku)
            case "anthropic":
            case "claude":
                return AnthropicChatModel.builder()
                        .apiKey(resolveKey(config, defaultAnthropicKey))
                        .modelName(config.name()) // e.g., "claude-3-5-sonnet-20240620"
                        .temperature(config.temperature())
                        .build();

            // 4. Ollama (Local Llama 3, Mistral)
            case "ollama":
                return OllamaChatModel.builder()
                        .baseUrl(defaultOllamaUrl)
                        .modelName(config.name()) // e.g., "llama3"
                        .temperature(config.temperature())
                        .build();

            // 5. DeepSeek / Groq (OpenAI-Compatible Providers)
            // Use 'openai' client but change the Base URL
            case "deepseek":
                return OpenAiChatModel.builder()
                        .apiKey(resolveKey(config, "YOUR_DEEPSEEK_KEY")) // Or inject via @Value
                        .baseUrl("https://api.deepseek.com")
                        .modelName(config.name()) // "deepseek-chat"
                        .temperature(config.temperature())
                        .build();

            case "groq":
                return OpenAiChatModel.builder()
                        .apiKey(resolveKey(config, "YOUR_GROQ_KEY"))
                        .baseUrl("https://api.groq.com/openai/v1")
                        .modelName(config.name()) // "llama3-70b-8192"
                        .temperature(config.temperature())
                        .build();
            case "azure":
            case "azure-openai":
                return AzureOpenAiChatModel.builder()
                        .endpoint(defaultAzureEndpoint)
                        .apiKey(resolveKey(config, defaultAzureKey))
                        // In Azure, 'modelName' usually refers to the 'Deployment Name'
                        .deploymentName(config.name())
                        .temperature(config.temperature())
                        .logRequestsAndResponses(true) // Helpful for debugging Azure errors
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
    }

    // Helper: Use agent-specific key if provided in YAML, otherwise use global
    // default
    private String resolveKey(AgentConfig.ModelConfig config, String defaultKey) {
        // If your ModelConfig has an apiKey field, use it. Otherwise default.
        // Assuming ModelConfig doesn't expose raw keys for security, we usually just
        // use default.
        // But if you added 'apiKey' to your YAML for overrides:
        /*
         * if (StringUtils.hasText(config.apiKey())) {
         * return config.apiKey();
         * }
         */
        if (!StringUtils.hasText(defaultKey)) {
            throw new IllegalStateException("Missing API Key for provider: " + config.provider());
        }
        return defaultKey;
    }
}