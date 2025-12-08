package com.sgr.ai.common.service;

import com.sgr.ai.common.FileWatcherService;
import com.sgr.ai.common.FileWatcherService.FileChangeEvent;
import com.sgr.ai.common.config.AgentDefinition;
import com.sgr.ai.common.config.WorkflowFileChangedEvent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Business Logic Service.
 * Manages the lifecycle of Agents and Workflows.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, AgentDefinition> agentCache = new ConcurrentHashMap<>();
    private final AgentLoaderService loaderService;
    private final FileWatcherService fileWatcherService; // Injected
    private final ApplicationEventPublisher eventPublisher; // <--- NEW
    private final Path agentsDirectory;

    @Autowired
    public AgentRegistry(AgentLoaderService loaderService,
            FileWatcherService fileWatcherService,
            ApplicationEventPublisher eventPublisher,
            @Value("${genai.base-path}") String basePath) {
        this.loaderService = loaderService;
        this.fileWatcherService = fileWatcherService;
        this.eventPublisher = eventPublisher; // Store it
        this.agentsDirectory = Paths.get(basePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Agent Registry. Root Path: {}", agentsDirectory);

        // 1. Initial Load
        loadAllConfigs();

        // 2. Start Watching (Delegated to FileWatcherService)
        fileWatcherService.startWatching(
                agentsDirectory,
                List.of(".yaml", ".md"),
                this::handleFileEvent // Pass our business logic as the callback
        );
    }

    /**
     * The unified callback method for any file event.
     */
    private void handleFileEvent(FileChangeEvent event) {
        File file = event.file();
        log.info("File Event: {} -> {}", event.type(), file.getAbsolutePath());

        if (event.type() == FileChangeEvent.Type.DELETED) {
            handleDeletion(file);
        } else {
            // Created or Modified
            if (isYamlFile(file)) {
                dispatchLoad(file.toPath());
            } else if (isMarkdownFile(file)) {
                reloadOwnerConfig(file);
            }
        }
    }

    private void handleDeletion(File file) {
        log.info("File deleted: {}", file.getName());
        if (isYamlFile(file)) {
            // Optional: Implement removal logic here
            log.info("Config deleted. Triggering reload check.");
        } else if (isMarkdownFile(file)) {
            reloadOwnerConfig(file);
        }
    }

    // =================================================================
    // EXISTING BUSINESS LOGIC (Unchanged)
    // =================================================================

    private void loadAllConfigs() {
        if (!Files.exists(agentsDirectory)) {
            try {
                Files.createDirectories(agentsDirectory);
            } catch (IOException e) {
                log.error("Failed to create dir", e);
                return;
            }
        }
        try (Stream<Path> paths = Files.walk(agentsDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(this::dispatchLoad);
        } catch (IOException e) {
            log.error("Failed to load initial configs", e);
        }
    }

    private void dispatchLoad(Path filePath) {
        String pathStr = filePath.toString();

        if (pathStr.contains("agents")) {
            loadAndRegisterAgent(filePath);
        } else if (pathStr.contains("workflows")) {
            // INSTAD OF CALLING ENGINE DIRECTLY, PUBLISH AN EVENT
            log.info("Publishing reload event for workflow: {}", filePath);
            eventPublisher.publishEvent(new WorkflowFileChangedEvent(filePath));
        }
    }

    private void loadAndRegisterAgent(Path filePath) {
        try {
            AgentDefinition agent = loaderService.loadAgent(filePath);
            agentCache.put(agent.id(), agent);
            log.info("Loaded Agent: [{}]", agent.id());
        } catch (Exception e) {
            log.error("Error loading agent file: " + filePath, e);
        }
    }

    private void reloadOwnerConfig(File promptFile) {
        File parentDir = promptFile.getParentFile();
        if (parentDir == null)
            return;
        File componentDir = parentDir.getParentFile();
        if (componentDir == null || !componentDir.isDirectory())
            return;

        File[] yamlFiles = componentDir.listFiles((dir, name) -> name.endsWith(".yaml"));
        if (yamlFiles != null) {
            for (File yaml : yamlFiles) {
                log.info("Prompt change in [{}]. Reloading owner: [{}]", promptFile.getName(), yaml.getName());
                dispatchLoad(yaml.toPath());
            }
        }
    }

    private boolean isYamlFile(File file) {
        return file.getName().toLowerCase().endsWith(".yaml");
    }

    private boolean isMarkdownFile(File file) {
        return file.getName().toLowerCase().endsWith(".md");
    }

    public List<AgentDefinition> getAllAgents() {
        return List.copyOf(agentCache.values());
    }

    public AgentDefinition getAgent(String id) {
        return agentCache.get(id);
    }
}