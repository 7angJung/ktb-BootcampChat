package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
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
    public void handleJoinRoom(SocketIOClient client, Object payload) {
        String roomId = extractRoomId(payload);
        if (roomId == null || roomId.isBlank()) {
            client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방 정보가 올바르지 않습니다."));
            return;
        }
        handleJoinRoom(client, roomId);
    }

    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
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

            Set<String> userIdsToLoad = new LinkedHashSet<>(room.getParticipantIds());
            if (room.getCreator() != null) {
                userIdsToLoad.add(room.getCreator());
            }
            Map<String, User> usersById = new HashMap<>();
            userRepository.findAllById(userIdsToLoad)
                    .forEach(user -> usersById.put(user.getId(), user));
            if (!usersById.containsKey(userId)) {
                userRepository.findById(userId)
                        .ifPresent(user -> usersById.put(user.getId(), user));
                if (!usersById.containsKey(userId)) {
                    client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                    return;
                }
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
                    .map(usersById::get)
                    .filter(Objects::nonNull)
                    .map(UserResponse::from)
                    .toList();

            User creatorUser = room.getCreator() == null ? null : usersById.get(room.getCreator());
            UserResponse creator = creatorUser == null ? null : UserResponse.from(creatorUser);

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

    private String extractRoomId(Object payload) {
        if (payload instanceof String roomId) {
            return roomId;
        }
        if (payload instanceof Map<?, ?> values) {
            Object roomId = values.get("roomId");
            return roomId != null ? roomId.toString() : null;
        }
        return null;
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
