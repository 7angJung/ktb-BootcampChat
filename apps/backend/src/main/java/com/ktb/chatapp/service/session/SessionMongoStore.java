package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

    @Autowired
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        Counter.builder("owner1.session.read")
                .register(meterRegistry)
                .increment();
        return sessionRepository.findByUserId(userId);
    }
    
    @Override
    public Session save(Session session) {
        Counter.builder("owner1.session.save")
                .register(meterRegistry)
                .increment();
        return sessionRepository.save(session);
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        Session session = sessionRepository.findByUserId(userId).orElse(null);
        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionRepository.delete(session);
        }
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
