package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LocalChatDataStoreTest {

    @Test
    void setOperationsDoNotLoseConcurrentAdds() {
        LocalChatDataStore store = new LocalChatDataStore();
        CompletableFuture<?>[] additions = IntStream.range(0, 100)
                .mapToObj(index -> CompletableFuture.runAsync(
                        () -> store.addToSet("rooms:user-1", "room-" + index)))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(additions).join();

        assertThat(store.getSet("rooms:user-1")).hasSize(100);
    }

    @Test
    void sizeCountsOnlyKeysWithTheRequestedPrefix() {
        LocalChatDataStore store = new LocalChatDataStore();
        store.set("conn:user-1", "socket-1");
        store.set("conn:user-2", "socket-2");
        store.addToSet("rooms:user-1", "room-1");

        assertThat(store.size("conn:")).isEqualTo(2);
    }
}
