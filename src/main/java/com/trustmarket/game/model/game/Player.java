package com.trustmarket.game.model.game;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {

    private String investTargetId;

    // Enums cho vai trò
    public enum Role {
        TRADER,     // Người mua
        INVESTOR    // Người bán
    }

    public enum SecretRole {
        ORACLE,     // Người biết đáp án
        SCAMMER,    // Kẻ lừa đảo
        NORMAL      // Người thường
    }

    // Basic info
    private String id;              // Session ID của Socket
    private String displayName;
    private String avatarUrl;

    // Game data
    @Builder.Default
    private double cash = 1000.0;   // Tiền mặc định

    private Role role;              // Vai trò công khai

    @JsonIgnore                     // Không gửi ra frontend
    private SecretRole secretRole;  // Vai trò bí mật

    @Builder.Default
    private boolean isReady = false;

    @Builder.Default
    private double blindBetAmount = 0.0;    // Số tiền cược vòng này

    private String selectedAnswer;          // A/B/C/D
}