package com.ktb.chatapp.websocket.socketio;

/**
 * Socket User Record
 * @param id user id
 * @param name user name
 * @param email user email
 * @param profileImage user profile image URL
 * @param authSessionId user auth session id
 * @param socketId user websocket session id
 */
public record SocketUser(
        String id,
        String name,
        String email,
        String profileImage,
        String authSessionId,
        String socketId
) {
    public SocketUser(String id, String name, String authSessionId, String socketId) {
        this(id, name, null, "", authSessionId, socketId);
    }
}
