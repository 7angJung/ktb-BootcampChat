package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomJoinHandlerTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRooms userRooms;
    @Mock private MessageLoader messageLoader;
    @Mock private SocketIOClient client;

    private RoomJoinHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RoomJoinHandler(
                roomRepository,
                userRepository,
                userRooms,
                messageLoader);
    }

    @Test
    void handleJoinRoom_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleJoinRoom(client, "room-1");

        verify(client).sendEvent(eq(JOIN_ROOM_ERROR), any());
    }

    @Test
    void handleJoinRoom_usesRestParticipantAndReturnsInitialRoomData() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder()
                .id("room-1")
                .name("room")
                .creator("user-1")
                .participantIds(Set.of("user-1"))
                .createdAt(LocalDateTime.now())
                .build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository, never()).addParticipant(anyString(), anyString());
        verify(client).joinRoom("room-1");
        verify(userRooms).add("user-1", "room-1");
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
    }

    @Test
    void handleJoinRoom_reconnectReturnsInitialDataWithoutAddingRoomAgain() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder()
                .id("room-1")
                .name("room")
                .creator("user-1")
                .participantIds(Set.of("user-1"))
                .createdAt(LocalDateTime.now())
                .build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);

        handler.handleJoinRoom(client, "room-1");

        verify(client).joinRoom("room-1");
        verify(userRooms, never()).add(anyString(), anyString());
        verify(roomRepository, never()).addParticipant(anyString(), anyString());
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
    }
}
