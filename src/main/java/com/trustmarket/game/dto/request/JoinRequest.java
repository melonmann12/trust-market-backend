package com.trustmarket.game.dto.request;
import lombok.Data;

@Data
public class JoinRequest {
    private String roomId;
    private String nickname;
    private String avatarUrl;
}