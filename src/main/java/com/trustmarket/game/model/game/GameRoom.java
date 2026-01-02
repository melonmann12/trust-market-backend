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

    // Thread-safe map cho multiplayer
    @Builder.Default
    private ConcurrentHashMap<String, Player> players = new ConcurrentHashMap<>();

    @Builder.Default
    private GameState currentState = GameState.WAITING;

    @Builder.Default
    private int timeRemaining = 0;  // Giây đếm ngược

    // Tạm thời dùng Map để chứa JSON câu hỏi
    private Map<String, Object> currentQuestion;

    // ============================================
    // Utility Methods
    // ============================================

    /**
     * Thêm người chơi vào phòng
     */
    public void addPlayer(Player player) {
        if (player != null && player.getId() != null) {
            players.put(player.getId(), player);
        }
    }

    /**
     * Xóa người chơi khỏi phòng
     */
    public void removePlayer(String playerId) {
        if (playerId != null) {
            players.remove(playerId);
        }
    }

    /**
     * Lấy số lượng người chơi hiện tại
     */
    public int getPlayerCount() {
        return players.size();
    }

    /**
     * Kiểm tra xem tất cả người chơi đã ready chưa
     */
    public boolean isAllPlayersReady() {
        if (players.isEmpty()) {
            return false;
        }
        return players.values().stream()
                .allMatch(Player::isReady);
    }

    /**
     * Lấy player theo ID
     */
    public Player getPlayer(String playerId) {
        return players.get(playerId);
    }
}