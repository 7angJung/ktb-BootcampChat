package com.ktb.chatapp.websocket.socketio;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local in-memory implementation of ChatDataStore using ConcurrentHashMap.
 * Thread-safe storage for chat-related data without external dependencies.
 */
public class LocalChatDataStore implements ChatDataStore {
    
    private final ConcurrentHashMap<String, Object> storage = new ConcurrentHashMap<>();
    
    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }
    
    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }
    
    @Override
    public void delete(String key) {
        storage.remove(key);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getSet(String key) {
        return get(key, Set.class)
                .map(value -> new HashSet<>((Set<String>) value))
                .orElseGet(HashSet::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addToSet(String key, String value) {
        storage.compute(key, (ignored, current) -> {
            Set<String> values = current instanceof Set<?> set
                    ? new HashSet<>((Set<String>) set)
                    : new HashSet<>();
            values.add(value);
            return values;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void removeFromSet(String key, String value) {
        storage.computeIfPresent(key, (ignored, current) -> {
            if (!(current instanceof Set<?> set)) {
                return current;
            }
            Set<String> values = new HashSet<>((Set<String>) set);
            values.remove(value);
            return values.isEmpty() ? null : values;
        });
    }

    @Override
    public int size(String keyPrefix) {
        return Math.toIntExact(storage.keySet().stream()
                .filter(key -> key.startsWith(keyPrefix))
                .count());
    }
}
