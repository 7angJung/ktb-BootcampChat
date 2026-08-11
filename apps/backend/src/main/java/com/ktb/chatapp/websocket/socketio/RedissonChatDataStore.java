package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Redis-backed application state shared by every Socket.IO instance. */
public class RedissonChatDataStore implements ChatDataStore {

    private static final String KEY_PREFIX = "chatapp:socket-state:";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public RedissonChatDataStore(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = bucket(key).get();
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize Socket.IO state: " + key, e);
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            bucket(key).set(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Socket.IO state: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        redissonClient.getKeys().delete(redisKey(key));
    }

    @Override
    public Set<String> getSet(String key) {
        return new HashSet<>(set(key).readAll());
    }

    @Override
    public void addToSet(String key, String value) {
        set(key).add(value);
    }

    @Override
    public void removeFromSet(String key, String value) {
        // RSet.remove is atomic. Keep an empty set key instead of a non-atomic
        // isEmpty -> delete sequence that could erase a concurrent add.
        set(key).remove(value);
    }

    @Override
    public int size(String keyPrefix) {
        int count = 0;
        for (String ignored : redissonClient.getKeys().getKeysByPattern(redisKey(keyPrefix) + "*")) {
            count++;
        }
        return count;
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(redisKey(key), StringCodec.INSTANCE);
    }

    private RSet<String> set(String key) {
        return redissonClient.getSet(redisKey(key), StringCodec.INSTANCE);
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
