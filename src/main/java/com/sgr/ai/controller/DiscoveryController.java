package com.sgr.ai.controller;

import com.sgr.ai.common.config.AgentDefinition;
import com.sgr.ai.common.config.WorkflowDefinition;
import com.sgr.ai.common.service.AgentRegistry;
import com.sgr.ai.common.service.WorkflowEngine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final AgentRegistry agentRegistry;
    private final WorkflowEngine workflowEngine;

    @Autowired
    public DiscoveryController(AgentRegistry agentRegistry, WorkflowEngine workflowEngine) {
        this.agentRegistry = agentRegistry;
        this.workflowEngine = workflowEngine;
    }

    /**
     * Retrieves a list of all available Agents, including their description, ID,
     * and allowed tools.
     */
    @GetMapping("/agents")
    public ResponseEntity<List<AgentMetadataDto>> getAvailableAgents() {
        List<AgentMetadataDto> agents = agentRegistry.getAllAgents().stream()
                .map(this::mapToAgentMetadata)
                .collect(Collectors.toList());

        return ResponseEntity.ok(agents);
    }

    /**
     * Retrieves a list of all configured Workflows.
     */
    @GetMapping("/workflows")
    public ResponseEntity<List<WorkflowMetadataDto>> getAvailableWorkflows() {
        List<WorkflowMetadataDto> workflows = workflowEngine.getAllWorkflows().stream()
                .map(this::mapToWorkflowMetadata)
                .collect(Collectors.toList());

        return ResponseEntity.ok(workflows);
    }

    // --- Mapper methods (to prevent leaking sensitive data) ---

    // DTO for Agent discovery
    private AgentMetadataDto mapToAgentMetadata(AgentDefinition def) {
        // NOTE: AgentDefinition contains systemPrompt and model config.
        // We MUST NOT expose the raw systemPrompt or API keys.
        return new AgentMetadataDto(
                def.id(),
                def.name(),
                def.description(), // Assuming you add a description field to AgentDefinition
                def.allowedTools());
    }

    // DTO for Workflow discovery
    private WorkflowMetadataDto mapToWorkflowMetadata(WorkflowDefinition config) {
        return new WorkflowMetadataDto(
                config.getId(),
                config.getName(),
                config.getType() // e.g., "CHAIN" or "ROUTER"
        );
    }

    // =========================================================================
    // DTO Definitions (MUST be defined separately to control exposed fields)
    // =========================================================================

    public record AgentMetadataDto(
            String id,
            String name,
            String description,
            List<String> allowedTools) {
    }

    public record WorkflowMetadataDto(
            String id,
            String name,
            String type) {
    }
}