package com.sgr.ai.common.config;

import java.util.List;

public class WorkflowDefinition {
    private String id;
    private String name;
    private String version;
    private String type; // CHAIN or ROUTER
    private List<Step> steps; // For CHAIN

    private String managerAgentId; // For ROUTER
    private List<String> allowedAgents; // For ROUTER
    private int maxSteps;

    // Getters and Setters needed for Jackson
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }

    public String getManagerAgentId() {
        return managerAgentId;
    }

    public void setManagerAgentId(String managerAgentId) {
        this.managerAgentId = managerAgentId;
    }

    public List<String> getAllowedAgents() {
        return allowedAgents;
    }

    public void setAllowedAgents(List<String> allowedAgents) {
        this.allowedAgents = allowedAgents;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public static class Step {
        private String stepId;
        private String agentId;
        private String inputSource;
        private String inputTemplate;

        public String getStepId() {
            return stepId;
        }

        public void setStepId(String stepId) {
            this.stepId = stepId;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getInputSource() {
            return inputSource;
        }

        public void setInputSource(String inputSource) {
            this.inputSource = inputSource;
        }

        public String getInputTemplate() {
            return inputTemplate;
        }

        public void setInputTemplate(String inputTemplate) {
            this.inputTemplate = inputTemplate;
        }
    }
}