package com.sgr.ai.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// @Component
public class RateLimitingInterceptor implements HandlerInterceptor {

    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";

    private final RateLimitingService rateLimitingService;

    public RateLimitingInterceptor(RateLimitingService rateLimitingService) {
        this.rateLimitingService = rateLimitingService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            String userId = jwt.getSubject(); // Typically the 'sub' claim is the user ID

            if (!rateLimitingService.tryAcquire(userId)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HEADER_RETRY_AFTER, "1"); // Suggest waiting 1 second
                response.setHeader(HEADER_RATE_LIMIT_REMAINING, "0");
                response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(rateLimitingService.getPermitsPerMinute())); // Reflects the configured per-minute limit
                response.getWriter().write("Too many requests. Please try again later.");
                return false; // Block the request
            }
            // Optionally, add rate limit headers for successful requests
            response.setHeader(HEADER_RATE_LIMIT_REMAINING, "1"); // Placeholder, actual remaining would require more complex tracking
            response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(rateLimitingService.getPermitsPerMinute())); // Reflects the configured per-minute limit
        }
        // If not authenticated or not a JWT principal, proceed without rate limiting
        // You might want to apply a default rate limit for unauthenticated users as well
        return true;
    }
}
