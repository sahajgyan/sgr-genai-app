package com.sgr.ai.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sgr.ai.common.AgentExecutionException;
import com.sgr.ai.common.config.AgentDefinition;
import com.sgr.ai.common.config.WorkflowDefinition;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import dev.langchain4j.exception.HttpException; // Ensure you have this import
import java.net.SocketTimeoutException;

/**
 * Orchestrates the execution of workflows defined in YAML files.
 * Supports "CHAIN" (Linear) and "ROUTER" (Dynamic) workflow types.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    // Infrastructure
    private final AgentRegistry agentRegistry;
    private final ChatModelFactory chatModelFactory; // The shared LLM (e.g., GPT-4)
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    // Config
    private final Path workflowsDirectory;

    // Cache: Map<WorkflowID, WorkflowConfigPOJO>
    private final Map<String, WorkflowDefinition> workflowCache = new ConcurrentHashMap<>();

    public WorkflowEngine(AgentRegistry agentRegistry,
            ChatModelFactory chatModel,
            @Value("${genai.base-path}") String basePath) {
        this.agentRegistry = agentRegistry;
        this.chatModelFactory = chatModel;
        this.workflowsDirectory = Paths.get(basePath, "workflows").toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        loadWorkflows();
    }

    // Method to return all loaded workflow configurations
    public List<WorkflowDefinition> getAllWorkflows() {
        return List.copyOf(workflowCache.values());
    }

    /**
     * Scans the /workflows directory and loads YAML configs into memory.
     */
    public void loadWorkflows() {
        if (!Files.exists(workflowsDirectory))
            return;

        try (Stream<Path> paths = Files.walk(workflowsDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(this::parseAndCache);
        } catch (IOException e) {
            log.error("Failed to load workflows", e);
        }
    }

    private void parseAndCache(Path path) {
        try {
            WorkflowDefinition config = yamlMapper.readValue(path.toFile(), WorkflowDefinition.class);
            workflowCache.put(config.getId(), config);
            log.info("Loaded Workflow: {}", config.getId());
        } catch (IOException e) {
            log.error("Invalid Workflow YAML: " + path, e);
        }
    }

    // ==================================================================================
    // EXECUTION LOGIC
    // ==================================================================================

    /**
     * Main Entry Point: Runs a workflow by ID.
     */
    public String runWorkflow(String workflowId, String initialInput) {
        WorkflowDefinition config = workflowCache.get(workflowId);
        if (config == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        log.info("Starting Workflow: [{}] Type: [{}]", config.getId(), config.getType());

        if ("CHAIN".equalsIgnoreCase(config.getType())) {
            return runChainWorkflow(config, initialInput);
        } else if ("ROUTER".equalsIgnoreCase(config.getType())) {
            return runRouterWorkflow(config, initialInput);
        } else {
            throw new UnsupportedOperationException("Unknown workflow type: " + config.getType());
        }
    }

    /**
     * Logic for Linear Chains (Step A -> Step B -> Step C)
     */
    private String runChainWorkflow(WorkflowDefinition config, String initialInput) {
        String currentData = initialInput;
        Map<String, String> executionContext = new HashMap<>();
        executionContext.put("USER_INPUT", initialInput);

        for (WorkflowDefinition.Step step : config.getSteps()) {
            log.debug("Executing Step: {}", step.getStepId());

            // 1. Resolve Input (Template or Direct)
            String stepInput = resolveInput(step, executionContext, currentData);

            // 2. Execute the Agent
            // NOTE: In a real app, you'd use AiServices to create a dynamic proxy here
            // For simplicity, we assume a helper method executes the agent
            String stepOutput = executeAgent(step.getAgentId(), stepInput);

            // 3. Update Context
            executionContext.put(step.getStepId(), stepOutput);
            currentData = stepOutput; // Pass forward to next step
        }

        return currentData; // Return result of the last step
    }

    /**
     * Logic for Dynamic Router (Manager decides next step)
     */
    private String runRouterWorkflow(WorkflowDefinition config, String initialInput) {
        String currentData = initialInput;
        int maxSteps = config.getMaxSteps() > 0 ? config.getMaxSteps() : 5;

        for (int i = 0; i < maxSteps; i++) {
            // 1. Ask the Manager Agent who to call next
            // We construct a specific prompt for the router
            String routingPrompt = buildRoutingPrompt(config, currentData);
            String routerResponse = executeAgent(config.getManagerAgentId(), routingPrompt);

            // 2. Parse Router JSON Response e.g., { "next_agent": "math_grader" }
            RouterDecision decision = parseRouterResponse(routerResponse);

            if ("FINISH".equalsIgnoreCase(decision.next_agent)) {
                return currentData;
            }

            // 3. Execute the chosen worker
            log.info("Router decided to call: {}", decision.next_agent);
            String workerResult = executeAgent(decision.next_agent, currentData);

            // 4. Update data for next loop
            currentData = workerResult;
        }

        return currentData;
    }

    // ==================================================================================
    // HELPER METHODS
    // ==================================================================================

    /**
     * Simulates executing an agent using LangChain4j.
     * In a full implementation, this constructs the AiService interface
     * dynamically.
     */

    // ... inside WorkflowEngine class ...

    private String executeAgent(String agentId, String userMessage) {
        AgentDefinition def = agentRegistry.getAgent(agentId);
        if (def == null) {
            throw new IllegalArgumentException("Agent ID not found: " + agentId);
        }

        try {
            ChatModel specifiedChatModel = chatModelFactory.getModel(def.model());

            // Sanitize Prompt (optional but recommended)
            String fullPrompt = def.systemPrompt() + "\n\nUser Input:\n" + userMessage;

            // Execute
            return specifiedChatModel.chat(fullPrompt);

        } catch (HttpException e) {
            // Handle HTTP errors from the Provider (OpenAI/Gemini)
            int code = e.statusCode();
            String errorMsg;
            boolean retryable = false;

            switch (code) {
                case 404:
                    errorMsg = "Model not found. Check your YAML config (provider/model name).";
                    break;
                case 429:
                    errorMsg = "Rate limit exceeded (Quota full). Please try again later.";
                    retryable = true;
                    break;
                case 401:
                    errorMsg = "Invalid API Key. Contact Administrator.";
                    break;
                case 500:
                case 503:
                    errorMsg = "AI Provider is currently down.";
                    retryable = true;
                    break;
                default:
                    errorMsg = "AI Provider Error: " + e.getMessage();
            }

            // Log it for the backend devs
            log.error("Agent [{}] failed with HTTP {}: {}", agentId, code, errorMsg);

            // Throw custom error for the Job Manager
            throw new AgentExecutionException(errorMsg, code, retryable, e);

        } catch (RuntimeException e) {
            // Handle Timeouts (often wrapped in RuntimeException in some clients)
            if (e.getCause() instanceof SocketTimeoutException) {
                log.error("Agent [{}] timed out.", agentId);
                throw new AgentExecutionException("AI didn't respond in time.", 408, true, e);
            }

            // Handle generic crashes
            log.error("Agent [{}] crashed unexpectedly.", agentId, e);
            throw new AgentExecutionException("Internal Agent Error: " + e.getMessage(), 500, false, e);
        }
    }

    private String resolveInput(WorkflowDefinition.Step step, Map<String, String> context, String lastOutput) {
        if (step.getInputTemplate() != null) {
            // Simple logic to replace {{step_id}} with actual values
            String template = step.getInputTemplate();
            for (Map.Entry<String, String> entry : context.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return template;
        }

        // Default behavior: Pass previous output
        return "USER_INPUT".equals(step.getInputSource()) ? context.get("USER_INPUT") : lastOutput;
    }

    private String buildRoutingPrompt(WorkflowDefinition config, String currentData) {
        // You would load this from the 'routerSystemPromptPath' defined in YAML
        // Hardcoded here for brevity
        return "Analyze this input: " + currentData +
                "\nDecide next step from allowed list: " + config.getAllowedAgents() +
                "\nReturn JSON: { \"next_agent\": \"NAME\" } or \"FINISH\"";
    }

    private RouterDecision parseRouterResponse(String json) {
        try {
            // Simple cleaning to remove markdown code blocks
            String cleanJson = json.replace("```json", "").replace("```", "").trim();
            return new ObjectMapper().readValue(cleanJson, RouterDecision.class);
        } catch (Exception e) {
            log.error("Failed to parse router decision", e);
            // Fail safe
            return new RouterDecision("FINISH");
        }
    }

    // ==================================================================================
    // INTERNAL DTOs (Map to YAML)
    // ==================================================================================

    public static class RouterDecision {
        public String next_agent;

        public RouterDecision() {
        }

        public RouterDecision(String next_agent) {
            this.next_agent = next_agent;
        }
    }
}