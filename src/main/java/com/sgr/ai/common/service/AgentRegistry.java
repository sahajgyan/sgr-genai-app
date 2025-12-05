package com.sgr.ai.common.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sgr.ai.common.config.AgentDefinition;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Watches a file system directory for Agent YAML configurations.
 * Reloads agents instantly when files are created, modified, or deleted.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    // Cache of active agents (Thread-safe)
    private final Map<String, AgentDefinition> agentCache = new ConcurrentHashMap<>();

    // The Loader Service we built previously
    private final AgentLoaderService loaderService;

    // Config: "src/main/resources/genai/agents/" or external path
    private final Path agentsDirectory;

    // Infrastructure for the background watcher
    private WatchService watchService;
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isRunning = false;

    public AgentRegistry(AgentLoaderService loaderService,
            @Value("${genai.base-path}") String basePath) {
        this.loaderService = loaderService;
        // Construct the full path to the 'agents' subfolder
        this.agentsDirectory = Paths.get(basePath, "agents").toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Agent Registry from: {}", agentsDirectory);

        // 1. Initial Load: Read all existing files immediately
        loadAllAgents();

        // 2. Background Watcher: Start listening for changes
        startFileWatcher();
    }

    /**
     * Scans the directory and loads all .yaml files
     */
    private void loadAllAgents() {
        if (!Files.exists(agentsDirectory)) {
            log.warn("Agent directory does not exist, skipping initial load: {}", agentsDirectory);
            return;
        }

        try (Stream<Path> paths = Files.walk(agentsDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .forEach(this::loadAndRegister);
        } catch (IOException e) {
            log.error("Failed to load initial agent configs", e);
        }
    }

    /**
     * Loads a specific file and updates the cache
     */
    private void loadAndRegister(Path filePath) {
        try {
            // Get just the filename (e.g., "math_grader.yaml")
            // String filename = filePath.getFileName().toString();
            log.info("Loading Agent  from file: {}", filePath);

            // Delegate to the LoaderService we wrote earlier
            AgentDefinition agent = loaderService.loadAgent(filePath);

            // Update Cache
            agentCache.put(agent.id(), agent);
            log.info("Loaded/Reloaded Agent: [{}] from file: {}", agent.id(), agent);

        } catch (Exception e) {
            log.error("Error loading agent file: " + filePath, e);
        }
    }

    /**
     * Removes an agent from cache if the file is deleted
     */
    private void removeAgent(Path filePath) {
        // We iterate to find which agent ID belonged to this filename
        // (Optimally, you might maintain a Map<Filename, AgentID> for faster lookups)
        String filename = filePath.getFileName().toString();
        log.info("Detected deletion of: {}", filename);

        // Simple removal (Re-load all is safer but slower, selective is better)
        // For now, we trigger a full reload to ensure consistency or logic to map
        // filename -> id
        loadAllAgents();
    }

    /**
     * The Background Thread implementation of Java NIO WatchService
     */
    private void startFileWatcher() {
        watcherExecutor.submit(() -> {
            try {
                this.watchService = FileSystems.getDefault().newWatchService();

                // Ensure directory exists before watching
                if (!Files.exists(agentsDirectory)) {
                    Files.createDirectories(agentsDirectory);
                }

                // Register events: Create, Modify, Delete
                agentsDirectory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                isRunning = true;
                log.info("File Watcher started on: {}", agentsDirectory);

                while (isRunning) {
                    WatchKey key;
                    try {
                        // Blocking call - waits for an event
                        key = watchService.take();
                    } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        // Handle Overflow (lost events)
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        // Get the filename context
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();
                        Path childPath = agentsDirectory.resolve(filename);

                        // Ignore non-yaml files (like temp files created by editors)
                        if (!filename.toString().endsWith(".yaml")) {
                            continue;
                        }

                        log.debug("Event detected: {} on {}", kind, filename);

                        if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                                kind == StandardWatchEventKinds.ENTRY_MODIFY) {

                            // Slight delay to ensure file write lock is released by the editor
                            Thread.sleep(100);
                            loadAndRegister(childPath);

                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            removeAgent(childPath);
                        }
                    }

                    // Reset key is vital to receive further events!
                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                }

            } catch (IOException | InterruptedException e) {
                log.error("File Watcher crashed", e);
            }
        });
    }

    // Method to return all loaded agent definitions
    public List<AgentDefinition> getAllAgents() {
        return List.copyOf(agentCache.values());
    }

    /**
     * Public API to retrieve agents
     */
    public AgentDefinition getAgent(String id) {
        return agentCache.get(id);
    }

    @PreDestroy
    public void cleanup() {
        isRunning = false;
        try {
            if (watchService != null) {
                watchService.close();
            }
            watcherExecutor.shutdownNow();
        } catch (IOException e) {
            log.error("Error closing WatchService", e);
        }
    }
}
