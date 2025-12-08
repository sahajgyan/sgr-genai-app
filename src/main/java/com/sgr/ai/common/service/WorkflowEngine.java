package com.sgr.ai.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sgr.ai.common.AgentExecutionException;
import com.sgr.ai.common.config.AgentDefinition;
import com.sgr.ai.common.config.WorkflowDefinition;
import com.sgr.ai.common.config.WorkflowFileChangedEvent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.exception.HttpException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Orchestrates the execution of workflows defined in YAML files.
 * Supports "CHAIN" (Linear) and "ROUTER" (Dynamic) workflow types.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    // Infrastructure
    private final AgentRegistry agentRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper(); // For parsing JSON responses

    // Config
    private final Path workflowsDirectory;

    // Cache: Map<WorkflowID, WorkflowDefinition>
    private final Map<String, WorkflowDefinition> workflowCache = new ConcurrentHashMap<>();

    public WorkflowEngine(AgentRegistry agentRegistry,
            ChatModelFactory chatModelFactory,
            @Value("${genai.base-path}") String basePath) {
        this.agentRegistry = agentRegistry;
        this.chatModelFactory = chatModelFactory;
        this.workflowsDirectory = Paths.get(basePath, "workflows").toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        loadAllWorkflows();
    }

    @EventListener
    public void onWorkflowFileChange(WorkflowFileChangedEvent event) {
        log.info("Received reload event for: {}", event.path());
        this.loadWorkflow(event.path());
    }

    // ==================================================================================
    // LOADING LOGIC
    // ==================================================================================

    public List<WorkflowDefinition> getAllWorkflows() {
        return List.copyOf(workflowCache.values());
    }

    /**
     * Scans the /workflows directory and loads all YAML configs into memory.
     */
    public void loadAllWorkflows() {
        if (!Files.exists(workflowsDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(workflowsDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(this::loadWorkflow);
        } catch (IOException e) {
            log.error("Failed to load workflows directory", e);
        }
    }

    /**
     * Loads (or reloads) a specific workflow file.
     * Public so AgentRegistry can call it on hot-reload.
     */
    public void loadWorkflow(Path path) {
        try {
            WorkflowDefinition config = yamlMapper.readValue(path.toFile(), WorkflowDefinition.class);
            workflowCache.put(config.getId(), config);
            log.info("Loaded/Reloaded Workflow: {}", config.getId());
        } catch (IOException e) {
            log.error("Invalid Workflow YAML: " + path, e);
        }
    }

    // ==================================================================================
    // EXECUTION LOGIC
    // ==================================================================================

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

    private String runChainWorkflow(WorkflowDefinition config, String initialInput) {
        String currentData = initialInput;
        Map<String, String> executionContext = new HashMap<>();
        executionContext.put("USER_INPUT", initialInput);

        for (WorkflowDefinition.Step step : config.getSteps()) {
            log.debug("Executing Step: {}", step.getStepId());

            // 1. Resolve Input
            String stepInput = resolveInput(step, executionContext, currentData);

            // 2. Execute Agent
            String stepOutput = executeAgent(step.getAgentId(), stepInput);

            // 3. Update Context
            executionContext.put(step.getStepId(), stepOutput);
            currentData = stepOutput;
        }

        return currentData;
    }

    private String runRouterWorkflow(WorkflowDefinition config, String initialInput) {
        String currentData = initialInput;
        int maxSteps = config.getMaxSteps() > 0 ? config.getMaxSteps() : 5;

        for (int i = 0; i < maxSteps; i++) {
            String routingPrompt = buildRoutingPrompt(config, currentData);
            String routerResponse = executeAgent(config.getManagerAgentId(), routingPrompt);

            RouterDecision decision = parseRouterResponse(routerResponse);

            if ("FINISH".equalsIgnoreCase(decision.next_agent)) {
                return currentData;
            }

            log.info("Router decided to call: {}", decision.next_agent);
            String workerResult = executeAgent(decision.next_agent, currentData);

            currentData = workerResult;
        }

        return currentData;
    }

    // ==================================================================================
    // AGENT EXECUTION & ERROR HANDLING
    // ==================================================================================

    private String executeAgent(String agentId, String userMessage) {
        AgentDefinition def = agentRegistry.getAgent(agentId);
        if (def == null) {
            throw new IllegalArgumentException("Agent ID not found: " + agentId);
        }

        try {
            ChatModel specifiedChatModel = chatModelFactory.getModel(def.model());

            // Construct full prompt
            String fullPrompt = def.systemPrompt() + "\n\nUser Input:\n" + userMessage;

            // Execute
            String response = specifiedChatModel.chat(fullPrompt);

            // Optional: Basic cleanup of markdown blocks if strictly needed for next steps
            return cleanMarkdown(response);

        } catch (HttpException e) {
            int code = e.statusCode();
            String errorMsg;
            boolean retryable = false;

            switch (code) {
                case 404:
                    errorMsg = "Model not found. Check YAML config (provider/model name).";
                    break;
                case 429:
                    errorMsg = "Rate limit exceeded (Quota full).";
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

            log.error("Agent [{}] failed with HTTP {}: {}", agentId, code, errorMsg);
            throw new AgentExecutionException(errorMsg, code, retryable, e);

        } catch (RuntimeException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                log.error("Agent [{}] timed out.", agentId);
                throw new AgentExecutionException("AI didn't respond in time.", 408, true, e);
            }

            log.error("Agent [{}] crashed unexpectedly.", agentId, e);
            throw new AgentExecutionException("Internal Agent Error: " + e.getMessage(), 500, false, e);
        }
    }

    // ==================================================================================
    // HELPERS
    // ==================================================================================

    private String resolveInput(WorkflowDefinition.Step step, Map<String, String> context, String lastOutput) {
        if (step.getInputTemplate() != null) {
            String template = step.getInputTemplate();
            for (Map.Entry<String, String> entry : context.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return template;
        }
        return "USER_INPUT".equals(step.getInputSource()) ? context.get("USER_INPUT") : lastOutput;
    }

    private String buildRoutingPrompt(WorkflowDefinition config, String currentData) {
        return "Analyze this input: " + currentData +
                "\nDecide next step from allowed list: " + config.getAllowedAgents() +
                "\nReturn JSON: { \"next_agent\": \"NAME\" } or \"FINISH\"";
    }

    private RouterDecision parseRouterResponse(String json) {
        try {
            String cleanJson = cleanMarkdown(json);
            return jsonMapper.readValue(cleanJson, RouterDecision.class);
        } catch (Exception e) {
            log.error("Failed to parse router decision: {}", json, e);
            return new RouterDecision("FINISH");
        }
    }

    /**
     * Removes ```json ... ``` wrapper from LLM output.
     */
    private String cleanMarkdown(String text) {
        if (text == null)
            return "";
        return text.replace("```json", "")
                .replace("```", "")
                .trim();
    }

    // ==================================================================================
    // DTOs
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