package com.sgr.ai.common.state;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobManager {

    // Thread-safe map to store job data (Id -> Status/Result)
    private static final Map<String, JobStatusResponse> JOB_STORE = new ConcurrentHashMap<>();

    // Status Constants
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * Creates a new job record in memory.
     * 
     * @return The unique job ID.
     */
    public String createJob(String workflowId) {
        String jobId = UUID.randomUUID().toString();
        JOB_STORE.put(jobId, new JobStatusResponse(jobId, STATUS_PENDING, null));
        return jobId;
    }

    /**
     * Updates the status of an existing job.
     */
    public void updateJobStatus(String jobId, String status, String result) {
        JobStatusResponse currentJob = JOB_STORE.get(jobId);
        if (currentJob != null) {
            JOB_STORE.put(jobId, new JobStatusResponse(jobId, status, result));
        } else {
            // Should not happen in normal flow
            throw new IllegalArgumentException("Job ID not found: " + jobId);
        }
    }

    /**
     * Retrieves the current status and result for a job.
     */
    public JobStatusResponse getJobStatus(String jobId) {
        return JOB_STORE.getOrDefault(jobId,
                new JobStatusResponse(jobId, STATUS_FAILED, "Job ID not found or expired."));
    }
}