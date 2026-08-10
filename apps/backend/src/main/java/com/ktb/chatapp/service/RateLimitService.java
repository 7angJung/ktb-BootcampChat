package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitDecision;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.net.InetAddress.getLocalHost;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitStore rateLimitStore;
    @Value("${HOSTNAME:''}")
    private String hostName;

    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }

    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        String actualClientId = hostName + ":" + clientId;
        Duration effectiveWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());
        Instant now = Instant.now();

        try {
            RateLimitDecision decision = rateLimitStore.incrementIfAllowed(
                    actualClientId,
                    maxRequests,
                    Duration.ofSeconds(windowSeconds),
                    now);
            long resetEpochSeconds = decision.expiresAt().getEpochSecond();

            if (!decision.allowed()) {
                long retryAfterSeconds = Math.max(1L, resetEpochSeconds - now.getEpochSecond());
                return RateLimitCheckResult.rejected(
                        maxRequests,
                        windowSeconds,
                        resetEpochSeconds,
                        retryAfterSeconds);
            }

            int remaining = Math.max(0, maxRequests - decision.count());
            long ttlSeconds = Math.max(1L, resetEpochSeconds - now.getEpochSecond());
            return RateLimitCheckResult.allowed(
                    maxRequests,
                    remaining,
                    windowSeconds,
                    resetEpochSeconds,
                    ttlSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = now.getEpochSecond() + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests,
                    maxRequests,
                    windowSeconds,
                    resetEpochSeconds,
                    windowSeconds);
        }
    }
}
