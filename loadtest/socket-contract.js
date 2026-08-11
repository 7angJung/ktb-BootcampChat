// 소켓 이벤트명 단일 소스. 값은 [asyncapi](apps/backend/.../asyncapi.yaml) 채널 address와
// 문자 단위 일치해야 한다. 부하기 스크립트는 이벤트명 리터럴 대신 이 상수만 사용한다.
module.exports = {
  CLIENT_EMIT: {
    JOIN_ROOM: 'joinRoom', // Payload: { roomId }
    LEAVE_ROOM: 'leaveRoom', // Payload: roomId (String)
    CHAT_MESSAGE: 'chatMessage', // Payload: { room, type, content, clientMessageId, fileData }
    FETCH_PREVIOUS_MESSAGES: 'fetchPreviousMessages', // Payload: { roomId, before }
    MARK_MESSAGES_AS_READ: 'markMessagesAsRead', // Payload: { roomId, messageIds }
    MESSAGE_REACTION: 'messageReaction', // Payload: { messageId, reaction, type }
  },
  SERVER_EMIT: {
    MESSAGE: 'message', // Payload: MessageResponse
    ERROR: 'error', // Payload: { code, message }
    JOIN_ROOM_SUCCESS: 'joinRoomSuccess', // Payload: JoinRoomSuccessResponse
    JOIN_ROOM_ERROR: 'joinRoomError', // Payload: { message }
    PREVIOUS_MESSAGES_LOADED: 'previousMessagesLoaded', // Payload: { messages, hasMore, oldestTimestamp }
    PARTICIPANTS_UPDATE: 'participantsUpdate', // Payload: List<UserDto>
    MESSAGES_READ: 'messagesRead', // Payload: { roomId, userId, messageIds, readAt }
    MESSAGE_REACTION_UPDATE: 'messageReactionUpdate', // Payload: { messageId, reactions }
    SESSION_ENDED: 'session_ended', // Payload: { reason, message } — snake_case가 서버 원문
  },
};
