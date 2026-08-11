package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RoomListIntegrationTest {

    @Autowired private RoomService roomService;
    @Autowired private RoomRepository roomRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        roomRepository.deleteAll();
    }

    @Test
    void roomListPagesAreStableAndRecentCountsAreAggregated() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
        IntStream.range(0, 21).forEach(index -> {
            Room saved = roomRepository.save(Room.builder()
                    .name("room " + index)
                    .participantIds(Set.of("user-1", "deleted-user"))
                    .build());
            saved.setCreatedAt(createdAt);
            roomRepository.save(saved);

            if (index == 0) {
                Message message = messageRepository.save(Message.builder()
                        .roomId(saved.getId())
                        .content("recent")
                        .build());
                message.setTimestamp(LocalDateTime.now().minusMinutes(1));
                messageRepository.save(message);
            }
        });

        RoomsResponse firstPage = roomService.getAllRooms(0, 20);
        RoomsResponse secondPage = roomService.getAllRooms(1, 20);

        assertThat(firstPage.getData()).hasSize(20);
        assertThat(firstPage.getMetadata().isHasMore()).isTrue();
        assertThat(secondPage.getData()).hasSize(1);
        assertThat(secondPage.getMetadata().isHasMore()).isFalse();
        assertThat(firstPage.getData()).extracting("id")
                .doesNotContainAnyElementsOf(secondPage.getData().stream().map(item -> item.getId()).toList());
        assertThat(firstPage.getData()).allSatisfy(room ->
                assertThat(room.getParticipantsCount()).isEqualTo(2));
        int recentMessageCount = firstPage.getData().stream()
                .mapToInt(room -> room.getRecentMessageCount()).sum()
                + secondPage.getData().stream()
                .mapToInt(room -> room.getRecentMessageCount()).sum();
        assertThat(recentMessageCount).isEqualTo(1);
    }

    @Test
    void roomsCollectionHasCreatedAtIdIndex() {
        assertThat(mongoTemplate.indexOps(Room.class).getIndexInfo())
                .anySatisfy(index -> {
                    assertThat(index.getName()).isEqualTo("created_at_id_desc");
                    assertThat(index.getIndexFields()).hasSize(2);
                });
    }
}
