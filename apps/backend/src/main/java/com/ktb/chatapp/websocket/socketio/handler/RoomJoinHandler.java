package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    
    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }
            
            var userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }
            
            var roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            Room room = roomOpt.get();
            if (!room.getParticipantIds().contains(userId)) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                    "message", "먼저 채팅방 입장 절차를 완료해주세요."
                ));
                return;
            }

            // REST 입장 API가 참가자 등록을 담당한다. Socket은 연결 상태만 복구한다.
            client.joinRoom(roomId);
            if (!userRooms.isInRoom(userId, roomId)) {
                userRooms.add(userId, roomId);
            }

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            // 참가자 정보 조회
            List<UserResponse> participants = room.getParticipantIds()
                    .stream()
                    .map(userRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(UserResponse::from)
                    .toList();

            UserResponse creator = participants.stream()
                .filter(participant -> Objects.equals(participant.getId(), room.getCreator()))
                .findFirst()
                .orElseGet(() -> room.getCreator() == null ? null : userRepository.findById(room.getCreator())
                    .map(UserResponse::from)
                    .orElse(null));

            RoomResponse roomResponse = RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .hasPassword(room.isHasPassword())
                .creator(creator)
                .participants(participants)
                .createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
                .isCreator(Objects.equals(room.getCreator(), userId))
                .build();
            
            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                .roomId(roomId)
                .room(roomResponse)
                .participants(participants)
                .messages(messageLoadResult.getMessages())
                .hasMore(messageLoadResult.isHasMore())
                .activeStreams(Collections.emptyList())
                .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
    }
    
    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }
}
