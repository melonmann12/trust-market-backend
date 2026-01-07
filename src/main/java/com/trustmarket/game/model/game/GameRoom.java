package com.trustmarket.game.model.game;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameRoom {

    private String roomId;
    private String hostId;

    @Builder.Default
    private ConcurrentHashMap<String, Player> players = new ConcurrentHashMap<>();

    @Builder.Default
    private GameState currentState = GameState.WAITING;

    @Builder.Default
    private int timeRemaining = 0;

    // --- 👇 PHẦN CẦN THÊM VÀO ĐÂY 👇 ---
    @Builder.Default
    private int currentRound = 1;  // Vòng hiện tại

    @Builder.Default
    private int totalRounds = 10;  // Tổng số vòng
    // -----------------------------------

    private Map<String, Object> currentQuestion;

    // Các hàm tiện ích (giữ nguyên như cũ)
    public void addPlayer(Player player) {
        if (player != null && player.getId() != null) {
            players.put(player.getId(), player);
        }
    }

    public Player getPlayer(String playerId) {
        return players.get(playerId);
    }

    public int getPlayerCount() {
        return players.size();
    }
}