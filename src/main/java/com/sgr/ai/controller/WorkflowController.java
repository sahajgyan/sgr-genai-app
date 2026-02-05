package com.sgr.ai.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sgr.ai.common.service.WorkflowEngine;
import com.sgr.ai.common.state.JobManager;
import com.sgr.ai.common.state.JobStatusResponse;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private JobManager jobManager;

    @Autowired
    private WorkflowEngine workflowEngine;

    // --- Configuration Properties ---
    
    @Value("${app.logging.transaction.enabled:false}")
    private boolean isTransactionLoggingEnabled;

    @Value("${app.logging.transaction.base-dir:./logs/transactions}")
    private String loggingBaseDir;

    /**
     * Submits an assessment to be processed asynchronously.
     * Returns 202 Accepted immediately with a jobId.
     */
    @PostMapping("/submit/{workflowId}")
    public ResponseEntity<JobStatusResponse> submitAssessment(
            @PathVariable String workflowId,
            @RequestBody String assessmentDataJson) {

        log.info("Received submission for workflow: {}", workflowId);

        // 1. Security Gates
        // ... Assume guards run ...

        // 2. Create the Job Record and get the ID
        String jobId = jobManager.createJob(workflowId);

        // 3. Dispatch the Job Asynchronously
        // NOTE: For @Async to work via self-invocation, you typically need to inject the controller into itself 
        // or move this method to a separate service bean.
        startAsyncExecution(jobId, workflowId, assessmentDataJson);

        // 4. Return IMMEDIATE 202 Response
        JobStatusResponse response = new JobStatusResponse(jobId, JobManager.STATUS_PENDING, null);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Executes the WorkflowEngine business logic in a separate thread.
     * Updated to include transaction logging.
     */
    @Async
    public void startAsyncExecution(String jobId, String workflowId, String assessmentDataJson) {
        try {
            jobManager.updateJobStatus(jobId, JobManager.STATUS_PROCESSING, "Workflow started.");
            log.info("Starting async processing for Job ID: {}", jobId);

            // --- Log Request ---
            saveTransactionLog(jobId, "request", assessmentDataJson);

            // --- Actual Business Logic ---
            String result = workflowEngine.runWorkflow(workflowId, assessmentDataJson);
            // --- End Business Logic ---

            // --- Log Response ---
            saveTransactionLog(jobId, "response", result);

            jobManager.updateJobStatus(jobId, JobManager.STATUS_COMPLETED, result);
            log.info("Workflow completed successfully for Job ID: {}", jobId);

        } catch (Exception e) {
            log.error("Workflow failed for Job ID: {}", jobId, e);
            jobManager.updateJobStatus(jobId, JobManager.STATUS_FAILED, "Processing failed: " + e.getMessage());
            
            // Optional: Log the error content as a response if needed
            saveTransactionLog(jobId, "error", e.getMessage());
        }
    }

    /**
     * Helper method to write transaction logs to disk.
     * File format: {jobid}_{timestamp}_{suffix}.json
     */
    private void saveTransactionLog(String jobId, String suffix, String content) {
        if (!isTransactionLoggingEnabled || content == null) {
            return;
        }

        try {
            // Ensure directory exists
            Path directoryPath = Paths.get(loggingBaseDir);
            if (!Files.exists(directoryPath)) {
                Files.createDirectories(directoryPath);
            }

            // Construct filename
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String filename = String.format("%s_%s_%s.json", jobId, timestamp, suffix);
            Path filePath = directoryPath.resolve(filename);

            // Write to file
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            log.debug("Logged transaction {} to file: {}", suffix, filePath);

        } catch (IOException e) {
            // We catch strictly here so logging failure does not fail the actual job
            log.error("Failed to write transaction log for Job ID: {}", jobId, e);
        }
    }

    /**
     * Client endpoint to poll for the status of a job.
     */
    @GetMapping("/status/{jobId}")
    public JobStatusResponse getStatus(@PathVariable String jobId) {
        return jobManager.getJobStatus(jobId);
    }
}