package com.sgr.ai.controller;

import com.sgr.ai.common.state.JobStatusResponse;
import com.sgr.ai.common.service.WorkflowEngine;
import com.sgr.ai.common.state.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Async;

// NOTE: You must place @EnableAsync on one of your main configuration classes (e.g., your @SpringBootApplication class)
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    @Autowired
    private JobManager jobManager;

    @Autowired
    private WorkflowEngine workflowEngine;

    /**
     * Submits an assessment to be processed asynchronously.
     * Returns 202 Accepted immediately with a jobId.
     */
    @PostMapping("/submit/{workflowId}")
    public ResponseEntity<JobStatusResponse> submitAssessment(
            @PathVariable String workflowId,
            @RequestBody String assessmentDataJson) {

        log.info("Received submission for workflow: {}", workflowId);

        // 1. Security Gates (AOP Interception happens here)
        // ... Assume guards run ...

        // 2. Create the Job Record and get the ID
        String jobId = jobManager.createJob(workflowId);

        // 3. Dispatch the Job Asynchronously
        startAsyncExecution(jobId, workflowId, assessmentDataJson);

        // 4. Return IMMEDIATE 202 Response
        JobStatusResponse response = new JobStatusResponse(jobId, JobManager.STATUS_PENDING, null);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Executes the WorkflowEngine business logic in a separate thread.
     * This method must be in a @Component/Service and use @EnableAsync.
     */
    @Async
    private void startAsyncExecution(String jobId, String workflowId, String assessmentDataJson) {
        try {
            jobManager.updateJobStatus(jobId, JobManager.STATUS_PROCESSING, "Workflow started.");
            log.info("Starting async processing for Job ID: {}", jobId);

            // --- Actual Business Logic ---
            String result = workflowEngine.runWorkflow(workflowId, assessmentDataJson);
            // --- End Business Logic ---

            jobManager.updateJobStatus(jobId, JobManager.STATUS_COMPLETED, result);
            log.info("Workflow completed successfully for Job ID: {}", jobId);

        } catch (Exception e) {
            log.error("Workflow failed for Job ID: {}", jobId, e);
            jobManager.updateJobStatus(jobId, JobManager.STATUS_FAILED, "Processing failed: " + e.getMessage());
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