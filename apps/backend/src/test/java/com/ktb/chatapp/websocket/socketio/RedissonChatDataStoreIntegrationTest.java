package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
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
class RedissonChatDataStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    private RedissonClient firstClient;
    private RedissonClient secondClient;
    private ConnectedUsers firstConnectedUsers;
    private ConnectedUsers secondConnectedUsers;
    private UserRooms firstUserRooms;
    private UserRooms secondUserRooms;

    @BeforeEach
    void setUp() {
        firstClient = createClient();
        secondClient = createClient();
        ObjectMapper objectMapper = new ObjectMapper();
        firstConnectedUsers = new ConnectedUsers(new RedissonChatDataStore(firstClient, objectMapper));
        secondConnectedUsers = new ConnectedUsers(new RedissonChatDataStore(secondClient, objectMapper));
        firstUserRooms = new UserRooms(new RedissonChatDataStore(firstClient, objectMapper));
        secondUserRooms = new UserRooms(new RedissonChatDataStore(secondClient, objectMapper));
    }

    @AfterEach
    void tearDown() {
        firstClient.getKeys().flushdb();
        firstClient.shutdown();
        secondClient.shutdown();
    }

    @Test
    void connectedUserWrittenOnOneNode_isVisibleAndRemovableOnAnotherNode() {
        SocketUser user = new SocketUser("user-1", "name", "email@example.com", "profile", "session", "socket-1");

        firstConnectedUsers.set(user.id(), user);

        assertThat(secondConnectedUsers.get(user.id())).isEqualTo(user);
        assertThat(secondConnectedUsers.size()).isEqualTo(1);

        secondConnectedUsers.del(user.id());
        assertThat(firstConnectedUsers.get(user.id())).isNull();
    }

    @Test
    void roomMembershipWrittenOnOneNode_isVisibleOnAnotherNode() {
        firstUserRooms.add("user-1", "room-1");
        secondUserRooms.add("user-1", "room-2");

        assertThat(firstUserRooms.get("user-1")).containsExactlyInAnyOrder("room-1", "room-2");
        assertThat(secondUserRooms.isInRoom("user-1", "room-1")).isTrue();

        secondUserRooms.remove("user-1", "room-1");
        assertThat(firstUserRooms.get("user-1")).containsExactly("room-2");
    }

    @Test
    void concurrentRoomAddsAreAtomicAcrossNodes() {
        CompletableFuture<?>[] additions = IntStream.range(0, 100)
                .mapToObj(index -> CompletableFuture.runAsync(() -> {
                    UserRooms rooms = index % 2 == 0 ? firstUserRooms : secondUserRooms;
                    rooms.add("user-1", "room-" + index);
                }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(additions).join();

        Set<String> rooms = firstUserRooms.get("user-1");
        assertThat(rooms).hasSize(100);
        assertThat(rooms).contains("room-0", "room-99");
    }

    private RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }
}
