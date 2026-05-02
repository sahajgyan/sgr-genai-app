package com.sgr.ai.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Value;

import com.google.common.util.concurrent.RateLimiter;

// @Service
public class RateLimitingService {

    // Map to store RateLimiter instances per user ID
    private final ConcurrentMap<String, RateLimiter> userRateLimiters = new ConcurrentHashMap<>();

    @Value("${app.rate-limit.permits-per-minute:1.0}") // Default to 1 request per minute
    private double permitsPerMinute;

    /**
     * Attempts to acquire a permit for the given user ID.
     * If no RateLimiter exists for the user, a new one is created.
     *
     * @param userId The unique identifier for the user.
     * @return true if a permit was acquired, false otherwise.
     */
    public boolean tryAcquire(String userId) {
        // Convert permits per minute to permits per second for Guava's RateLimiter
        double currentPermitsPerSecond = permitsPerMinute / 60.0;
        RateLimiter limiter = userRateLimiters.computeIfAbsent(userId, k -> RateLimiter.create(currentPermitsPerSecond));
        return limiter.tryAcquire();
    }

    /**
     * Sets the global permits per minute for new RateLimiters.
     * Existing RateLimiters will retain their current rate unless explicitly updated.
     * For dynamic updates to existing limiters, you would need a more sophisticated mechanism.
     *
     * @param permitsPerMinute The new rate in permits per minute.
     */
    public void setPermitsPerMinute(double permitsPerMinute) {
        this.permitsPerMinute = permitsPerMinute;
    }

    /**
     * Returns the configured permits per second (derived from permits per minute).
     * This is primarily for providing rate limit headers.
     * @return The configured permits per second.
     */
    public double getPermitsPerSecond() {
        return permitsPerMinute / 60.0;
    }

    /**
     * Returns the configured permits per minute.
     * This is useful for providing rate limit headers that reflect the configured unit.
     * @return The configured permits per minute.
     */
    public double getPermitsPerMinute() {
        return permitsPerMinute;
    }
}
