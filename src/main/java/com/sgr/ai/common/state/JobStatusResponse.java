package com.sgr.ai.common.state;

public record JobStatusResponse(
        String jobId,
        String status,
        String result) {
}