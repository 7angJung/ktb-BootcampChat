// 원자적 Rate Limit 갱신 결과를 표현한다.
package com.ktb.chatapp.service.ratelimit;

import java.time.Instant;

public record RateLimitDecision(boolean allowed, int count, Instant expiresAt) {
}
