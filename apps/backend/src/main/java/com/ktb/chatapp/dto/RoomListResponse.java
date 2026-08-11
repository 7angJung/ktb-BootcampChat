package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "채팅방 목록 전용 경량 응답")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomListResponse {

    @JsonProperty("_id")
    private String id;

    private String name;

    private boolean hasPassword;

    private int participantsCount;

    private int recentMessageCount;

    @JsonIgnore
    private LocalDateTime createdAtDateTime;

    @JsonGetter("createdAt")
    public String getCreatedAt() {
        return createdAtDateTime == null
                ? null
                : createdAtDateTime.atZone(ZoneId.systemDefault()).toInstant().toString();
    }
}
