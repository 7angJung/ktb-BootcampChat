package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.corundumstudio.socketio.store.pubsub.ConnectMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubType;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SocketIORedissonPubSubIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    private RedissonStoreFactory publisherFactory;
    private RedissonStoreFactory subscriberFactory;

    @BeforeEach
    void setUp() {
        publisherFactory = new RedissonStoreFactory(createClient());
        subscriberFactory = new RedissonStoreFactory(createClient());
    }

    @AfterEach
    void tearDown() {
        publisherFactory.shutdown();
        subscriberFactory.shutdown();
    }

    @Test
    void publishOnOneFactory_isReceivedByAnotherFactory() throws InterruptedException {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<ConnectMessage> receivedMessage = new AtomicReference<>();

        subscriberFactory.pubSubStore().subscribe(
                PubSubType.CONNECT,
                message -> {
                    receivedMessage.set(message);
                    received.countDown();
                },
                ConnectMessage.class);

        publisherFactory.pubSubStore().publish(PubSubType.CONNECT, new ConnectMessage(sessionId));

        assertThat(received.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(receivedMessage.get().getSessionId()).isEqualTo(sessionId);
    }

    private RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }
}
