package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
                roomRepository,
                userRepository,
                recentMessageCounter,
                passwordEncoder,
                eventPublisher);
    }

    @Test
    void getAllRooms_emptyPageReturnsAccurateMetadataWithoutUserLookup() {
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        RoomsResponse response = roomService.getAllRooms(0, 20);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
        assertThat(response.getMetadata().getTotal()).isZero();
        assertThat(response.getMetadata().isHasMore()).isFalse();
        verifyNoInteractions(userRepository);
        verify(recentMessageCounter).countRecentMessages(List.of());
    }

    @Test
    void getAllRooms_twentyOfTwentyOneReturnsLightweightPageAndHasMore() {
        List<Room> rooms = IntStream.range(0, 20)
                .mapToObj(this::room)
                .toList();
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rooms, PageRequest.of(0, 20), 21));
        when(recentMessageCounter.countRecentMessages(anyCollection()))
                .thenReturn(Map.of("room-0", 7));

        RoomsResponse response = roomService.getAllRooms(0, 20);

        assertThat(response.getData()).hasSize(20);
        assertThat(response.getData().getFirst().getParticipantsCount()).isEqualTo(2);
        assertThat(response.getData().getFirst().getRecentMessageCount()).isEqualTo(7);
        assertThat(response.getMetadata().getTotal()).isEqualTo(21);
        assertThat(response.getMetadata().getTotalPages()).isEqualTo(2);
        assertThat(response.getMetadata().isHasMore()).isTrue();
        assertThat(response.getMetadata().getCurrentCount()).isEqualTo(20);
        assertThat(response.getMetadata().getSort().getField()).isEqualTo("createdAt");
        verifyNoInteractions(userRepository);

        verify(recentMessageCounter).countRecentMessages(anyCollection());
        verify(roomRepository).findAll(any(Pageable.class));
    }

    @Test
    void getAllRooms_exactlyTwentyRoomsHasNoMorePage() {
        List<Room> rooms = IntStream.range(0, 20).mapToObj(this::room).toList();
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rooms, PageRequest.of(0, 20), 20));
        when(recentMessageCounter.countRecentMessages(anyCollection())).thenReturn(Map.of());

        RoomsResponse response = roomService.getAllRooms(0, 20);

        assertThat(response.getData()).hasSize(20);
        assertThat(response.getMetadata().isHasMore()).isFalse();
        assertThat(response.getMetadata().getTotalPages()).isEqualTo(1);
    }

    @Test
    void getAllRooms_lessThanPageSizeReturnsCurrentCount() {
        List<Room> rooms = IntStream.range(0, 7).mapToObj(this::room).toList();
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rooms, PageRequest.of(0, 20), 7));
        when(recentMessageCounter.countRecentMessages(anyCollection())).thenReturn(Map.of());

        RoomsResponse response = roomService.getAllRooms(0, 20);

        assertThat(response.getMetadata().getCurrentCount()).isEqualTo(7);
        assertThat(response.getMetadata().isHasMore()).isFalse();
    }

    @Test
    void getAllRooms_missingParticipantUsersStillUsesStoredIdCount() {
        Room room = room(1);
        room.setParticipantIds(Set.of("existing", "deleted"));
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(room), PageRequest.of(0, 20), 1));
        when(recentMessageCounter.countRecentMessages(anyCollection())).thenReturn(Map.of());

        RoomsResponse response = roomService.getAllRooms(0, 20);

        assertThat(response.getData().getFirst().getParticipantsCount()).isEqualTo(2);
        verifyNoInteractions(userRepository);
    }

    private Room room(int index) {
        return Room.builder()
                .id("room-" + index)
                .name("room " + index)
                .createdAt(LocalDateTime.now().minusMinutes(index))
                .participantIds(Set.of("user-1", "user-2"))
                .build();
    }
}
