package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Duration;
import java.time.Instant;
import com.ktb.chatapp.repository.RateLimitRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 * Uses RateLimitRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {
    
    private final RateLimitRepository rateLimitRepository;
    private final MongoTemplate mongoTemplate;
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

    @Autowired
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public Optional<RateLimit> findByClientId(String clientId) {
        increment("owner1.rate_limit.read");
        return rateLimitRepository.findByClientId(clientId);
    }
    
    @Override
    public RateLimit save(RateLimit rateLimit) {
        increment("owner1.rate_limit.save");
        return rateLimitRepository.save(rateLimit);
    }

    @Override
    public RateLimitDecision incrementIfAllowed(
            String clientId,
            int maxRequests,
            Duration window,
            Instant now) {
        increment("owner1.rate_limit.atomic_update");
        Instant expiresAt = now.plus(window);

        RateLimit expired = mongoTemplate.findAndModify(
                new Query(new Criteria().andOperator(
                        Criteria.where("clientId").is(clientId),
                        new Criteria().orOperator(
                                Criteria.where("expiresAt").exists(false),
                                Criteria.where("expiresAt").lte(now)))),
                new Update().set("count", 1).set("expiresAt", expiresAt),
                FindAndModifyOptions.options().returnNew(true),
                RateLimit.class);
        if (expired != null) {
            return new RateLimitDecision(true, expired.getCount(), expired.getExpiresAt());
        }

        RateLimit active = mongoTemplate.findAndModify(
                new Query(new Criteria().andOperator(
                        Criteria.where("clientId").is(clientId),
                        Criteria.where("expiresAt").gt(now),
                        Criteria.where("count").lt(maxRequests))),
                new Update().inc("count", 1),
                FindAndModifyOptions.options().returnNew(true),
                RateLimit.class);
        if (active != null) {
            return new RateLimitDecision(true, active.getCount(), active.getExpiresAt());
        }

        RateLimit current = mongoTemplate.findOne(
                Query.query(Criteria.where("clientId").is(clientId)),
                RateLimit.class);
        if (current != null) {
            increment("owner1.rate_limit.read");
            return new RateLimitDecision(false, current.getCount(), current.getExpiresAt());
        }

        try {
            increment("owner1.rate_limit.save");
            RateLimit created = mongoTemplate.insert(RateLimit.builder()
                    .clientId(clientId)
                    .count(1)
                    .expiresAt(expiresAt)
                    .build());
            return new RateLimitDecision(true, created.getCount(), created.getExpiresAt());
        } catch (org.springframework.dao.DuplicateKeyException race) {
            return incrementIfAllowed(clientId, maxRequests, window, now);
        }
    }

    private void increment(String name) {
        Counter.builder(name).register(meterRegistry).increment();
    }
}
