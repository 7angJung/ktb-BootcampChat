package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.ReadStatusUpdate;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageReadStatusService messageReadStatusService;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReadHandler(
                socketIOServer,
                messageReadStatusService,
                roomRepository,
                userRepository);
    }

    @Test
    void handleMarkAsRead_rejectsUnauthorizedClient() {
        MarkAsReadRequest request = request("message-1");
        when(client.get("user")).thenReturn(null);

        handler.handleMarkAsRead(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(messageReadStatusService, never()).updateReadStatus(anyString(), anyList(), anyString());
    }

    @Test
    void handleMarkAsRead_updatesStatusAndBroadcasts() {
        MarkAsReadRequest request = request("message-1");
        Room room = Room.builder().id("room-1").participantIds(Set.of("user-1")).build();
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(messageReadStatusService.updateReadStatus("room-1", List.of("message-1"), "user-1"))
                .thenReturn(new ReadStatusUpdate(
                        List.of("message-1"),
                        "2026-08-10T00:00:00Z"));
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService).updateReadStatus("room-1", List.of("message-1"), "user-1");
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("room-1", response.getRoomId());
        assertEquals("user-1", response.getUserId());
        assertEquals(List.of("message-1"), response.getMessageIds());
        assertEquals("2026-08-10T00:00:00Z", response.getReadAt());
    }

    private MarkAsReadRequest request(String messageId) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setRoomId("room-1");
        request.setMessageIds(List.of(messageId));
        return request;
    }
}
