package com.sgr.ai.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sgr.ai.common.config.AgentConfig;
import com.sgr.ai.common.config.AgentDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentLoaderService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoaderService.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    // Configured in application.yml: genai.base-path=src/main/resources/genai/
    @Value("${genai.base-path}")
    private String basePath;

    // ThreadLocal to hold the base path for prompts during recursive loading
    private final ThreadLocal<Path> promptBaseDir = new ThreadLocal<>();

    /**
     * Loads an agent given the full Path to its YAML file.
     * 
     * @param yamlPath The absolute path to the agent's YAML file.
     */
    public AgentDefinition loadAgent(Path yamlPath) {
        try {
            // 1. DETERMINE THE CORRECT PROMPT BASE DIRECTORY
            // Assumption: Path structure is .../basePath/agent_name_folder/agent.yml
            // The prompt files are in: .../basePath/agent_name_folder/prompts/

            // Get the parent directory of the YAML file (e.g., /agent_name_folder)
            Path agentConfigDir = yamlPath.getParent();

            // The prompt base directory is now [yaml's parent directory] + "prompts"
            promptBaseDir.set(agentConfigDir.normalize());

            // Set the context for all subsequent readFile calls
            // promptBaseDir.set(newPromptBaseDir);

            // 2. Read the YAML Configuration
            AgentConfig config = yamlMapper.readValue(yamlPath.toFile(), AgentConfig.class);

            // 3. Load and Process Prompts
            // The paths in YAML (e.g., 'system.md') are now resolved relative to the
            // newPromptBaseDir
            String rawSystemPrompt = readFile(config.systemPromptPath());
            String systemPrompt = processPrompt(rawSystemPrompt, config.metadata());

            String rawUserPrompt = readFile(config.userPromptPath());
            String userPrompt = processPrompt(rawUserPrompt, config.metadata());

            // 4. Return the Hydrated Definition
            return new AgentDefinition(
                    config.id(),
                    config.name(),
                    "",
                    systemPrompt,
                    userPrompt,
                    config.model(),
                    config.allowedTools(),
                    config.metadata());

        } catch (IOException e) {
            log.error("Failed to load agent from path: " + yamlPath, e);
            throw new RuntimeException("Agent load failed", e);
        } finally {
            // CRITICAL: Clean up the ThreadLocal after the operation completes
            promptBaseDir.remove();
        }
    }

    /**
     * Reads a file using the current ThreadLocal prompt base directory.
     * Supports relative paths defined in YAML or includes.
     */
    private String readFile(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            return "";
        }

        Path baseDir = promptBaseDir.get();
        if (baseDir == null) {
            throw new IllegalStateException(
                    "Prompt base directory context not set. The YAML file must be loaded first.");
        }

        // Resolve the path relative to the specific agent's 'prompts' folder
        Path fullPath = baseDir.resolve(relativePath).normalize();

        // Security check: Ensure we aren't escaping the project's base directory
        // if (!fullPath.(Paths.get(basePath).normalize())) {
        // throw new SecurityException("Attempted path traversal detected: " +
        // fullPath);
        // }

        return Files.readString(fullPath);
    }

    /**
     * Handles:
     * 1. {{includes}} logic (Recursive loading)
     * 2. {{placeholder}} replacement from metadata
     */
    private String processPrompt(String content, Map<String, Object> metadata) throws IOException {
        if (content == null)
            return "";

        // Step A: Handle {{include: path/to/file.md}}
        Pattern includePattern = Pattern.compile("\\{\\{include:(.*?)}}");
        Matcher matcher = includePattern.matcher(content);

        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String includePath = matcher.group(1).trim();
            // Recursive call uses the updated readFile method which resolves path correctly
            String includedContent = readFile(includePath);
            matcher.appendReplacement(builder, processPrompt(includedContent, metadata));
        }
        matcher.appendTail(builder);
        String processed = builder.toString();

        // Step B: Handle Standard Metadata Placeholders {{key}}
        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = "{{" + entry.getKey() + "}}";
                String value = String.valueOf(entry.getValue());
                processed = processed.replace(key, value);
            }
        }

        return processed;
    }
}