package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태를 방 단위 batch로 원자적으로 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

    @Autowired
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public ReadStatusUpdate updateReadStatus(
            String roomId,
            List<String> messageIds,
            String userId) {
        if (roomId == null || roomId.isBlank() || messageIds == null || messageIds.isEmpty()) {
            return ReadStatusUpdate.empty();
        }

        Set<String> uniqueMessageIds = new LinkedHashSet<>(messageIds);
        LocalDateTime readAt = LocalDateTime.now(ZoneOffset.UTC);

        try {
            Query unreadQuery = new Query(new Criteria().andOperator(
                    Criteria.where("_id").in(uniqueMessageIds),
                    Criteria.where("room").is(roomId),
                    new Criteria().orOperator(
                            Criteria.where("readers").exists(false),
                            Criteria.where("readers.userId").ne(userId))));

            increment("owner1.message.read");
            List<String> candidateIds = mongoTemplate.find(unreadQuery, Message.class).stream()
                    .map(Message::getId)
                    .toList();

            if (candidateIds.isEmpty()) {
                return ReadStatusUpdate.empty();
            }

            Update update = new Update().addToSet(
                    "readers",
                    Message.MessageReader.builder()
                            .userId(userId)
                            .readAt(readAt)
                            .build());
            increment("owner1.message.bulk_update");
            mongoTemplate.updateMulti(unreadQuery, update, Message.class);

            increment("owner1.message.read");
            Query appliedQuery = new Query(new Criteria().andOperator(
                    Criteria.where("_id").in(candidateIds),
                    Criteria.where("room").is(roomId),
                    Criteria.where("readers").elemMatch(
                            Criteria.where("userId").is(userId).and("readAt").is(readAt))));

            List<String> appliedIds = mongoTemplate.find(appliedQuery, Message.class).stream()
                    .map(Message::getId)
                    .toList();

            return new ReadStatusUpdate(appliedIds, readAt.atOffset(ZoneOffset.UTC).toInstant().toString());
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
            return ReadStatusUpdate.empty();
        }
    }

    private void increment(String name) {
        Counter.builder(name).register(meterRegistry).increment();
    }
}
