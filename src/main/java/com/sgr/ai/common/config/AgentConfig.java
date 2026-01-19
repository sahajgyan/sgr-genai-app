package com.sgr.ai.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

// Represents the raw data inside 'math_grader.yaml'
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(
                String id,
                String name,
                String version,
                String description,

                @JsonProperty("systemPromptPath") String systemPromptPath, // e.g., "prompts/math/grader_system.md"

                @JsonProperty("userPromptPath") String userPromptPath, // e.g., "prompts/math/grader_user.md"

                ModelConfig model,

                List<String> allowedTools,

                // Response format configuration (optional)
                Map<String, Object> responseFormat,

                // Metadata for {{placeholders}}
                Map<String, Object> metadata) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ModelConfig(
                        String provider, // "openai", "azure", "ollama", "gemini", "anthropic"
                        String name, // "gpt-4", "llama3", "gemini-2.0-flash"
                        Double temperature,
                        Integer maxTokens) {
        }
}
