package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitDecision;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;

    @Mock
    private RateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
    }

    @Test
    @DisplayName("최초 요청은 host-prefixed clientId로 원자 증가하고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_UsesHostPrefixedClientId() {
        Instant expiresAt = Instant.now().plusSeconds(30);
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 1, expiresAt));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);
        verify(rateLimitStore).incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class));
    }

    @Test
    @DisplayName("기존 카운트가 한도 미만이면 증가시켜 저장한다")
    void checkRateLimit_ExistingBelowLimit_IncrementsCount() {
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 2, Instant.now().plusSeconds(20)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
        verify(rateLimitStore).incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class));
    }

    @Test
    @DisplayName("기존 카운트가 한도에 도달하면 retry-after와 reset epoch를 반환하고 저장하지 않는다")
    void checkRateLimit_LimitReached_ReturnsRetryAfterWithoutSaving() {
        Instant expiresAt = Instant.now().plusSeconds(10);
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(false, 3, expiresAt));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, 10L);
        assertThat(result.resetEpochSeconds()).isEqualTo(expiresAt.getEpochSecond());
        verify(rateLimitStore).incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class));
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(1)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(1)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("만료된 저장소 문서는 새 window로 리셋된다")
    void checkRateLimit_ExpiredStoredRateLimit_StartsNewWindow() {
        Instant expiresAt = Instant.now().plusSeconds(30);
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 1, expiresAt));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);
        assertThat(result.resetEpochSeconds()).isGreaterThan(Instant.now().getEpochSecond());
        verify(rateLimitStore).incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class));
    }

    @Test
    @DisplayName("저장소 실패 시 요청은 허용하고 전체 한도를 남긴다")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.incrementIfAllowed(
                eq(STORE_CLIENT_ID), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 저장소 key로 처리된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedNullKey() {
        String storeClientId = HOST_NAME + ":null";
        when(rateLimitStore.incrementIfAllowed(
                eq(storeClientId), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class)))
                .thenReturn(new RateLimitDecision(true, 1, Instant.now().plusSeconds(30)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).incrementIfAllowed(
                eq(storeClientId), eq(3), eq(Duration.ofSeconds(30)), any(Instant.class));
    }
}
