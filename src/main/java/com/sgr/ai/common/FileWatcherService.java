package com.sgr.ai.common;

import jakarta.annotation.PreDestroy;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Generic service to watch directories recursively using Apache Commons IO.
 */
@Service
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);
    private FileAlterationMonitor monitor;

    // --- Inner Record for Event Data ---
    public record FileChangeEvent(File file, Type type) {
        public enum Type {
            CREATED, MODIFIED, DELETED
        }
    }

    /**
     * Starts watching a directory tree.
     * 
     * @param rootDir    The directory to watch.
     * @param extensions List of extensions to watch (e.g., ".yaml", ".md").
     * @param listener   Callback function to handle events.
     */
    public void startWatching(Path rootDir, List<String> extensions, Consumer<FileChangeEvent> listener) {
        try {
            File directory = rootDir.toFile();
            if (!directory.exists()) {
                log.warn("Directory does not exist, skipping watcher: {}", directory);
                return;
            }

            // 1. Build Recursive Filter
            // Allow all Directories (to enable recursion)
            IOFileFilter directories = FileFilterUtils.and(
                    FileFilterUtils.directoryFileFilter(),
                    FileFilterUtils.notFileFilter(FileFilterUtils.nameFileFilter(".git")),
                    FileFilterUtils.notFileFilter(FileFilterUtils.nameFileFilter("target")));

            // Allow specific File Extensions
            List<IOFileFilter> suffixFilters = extensions.stream()
                    .map(ext -> FileFilterUtils.suffixFileFilter(ext))
                    .collect(Collectors.toList());

            // Convert List to Array safely for the varargs method
            IOFileFilter files = FileFilterUtils.or(suffixFilters.toArray(new IOFileFilter[0]));

            // Combine: (Is Directory) OR (Is Matching File)
            IOFileFilter filter = FileFilterUtils.or(directories, files);

            // 2. Create Observer
            FileAlterationObserver observer = new FileAlterationObserver(directory, filter);

            // 3. Attach Listener that delegates to our Consumer
            observer.addListener(new FileAlterationListenerAdaptor() {
                @Override
                public void onFileCreate(File file) {
                    listener.accept(new FileChangeEvent(file, FileChangeEvent.Type.CREATED));
                }

                @Override
                public void onFileChange(File file) {
                    listener.accept(new FileChangeEvent(file, FileChangeEvent.Type.MODIFIED));
                }

                @Override
                public void onFileDelete(File file) {
                    listener.accept(new FileChangeEvent(file, FileChangeEvent.Type.DELETED));
                }
            });

            // 4. Start Monitor (Poll every 1s)
            monitor = new FileAlterationMonitor(1000, observer);
            monitor.start();
            log.info("File Watcher started on: {} for extensions: {}", rootDir, extensions);

        } catch (Exception e) {
            log.error("Failed to start file watcher", e);
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (monitor != null) {
                monitor.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping file watcher", e);
        }
    }
}