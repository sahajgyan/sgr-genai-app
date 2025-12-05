package com.sgr.ai.common.config;

import java.util.List;
import java.util.Map;

// The fully loaded agent ready for execution
public record AgentDefinition(
                String id,
                String name,
                String description,
                String systemPrompt, // The ACTUAL text content (loaded from MD)
                String userPrompt, // The ACTUAL text content (loaded from MD)
                AgentConfig.ModelConfig model,
                List<String> allowedTools,
                Map<String, Object> metadata) {
}
