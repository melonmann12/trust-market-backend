package com.trustmarket.game.service;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.model.game.GameState;
import com.trustmarket.game.model.game.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class GameEngine {

    private final SimpMessagingTemplate messagingTemplate;
    private final ScheduledExecutorService scheduler;

    // Quản lý tất cả các phòng đang chạy
    private final ConcurrentHashMap<String, GameRoom> activeRooms;

    // Quản lý các timer đang chạy cho từng phòng
    private final ConcurrentHashMap<String, ScheduledFuture<?>> roomTimers;

    // Cấu hình thời gian cho mỗi state (giây)
    private static final Map<GameState, Integer> STATE_DURATION = Map.of(
            GameState.BLIND_BET, 30,
            GameState.ROLE_ASSIGN, 10,
            GameState.MARKET_CHAT, 60,
            GameState.CLOSING, 20,
            GameState.CALCULATION, 10
    );

    public GameEngine(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.scheduler = Executors.newScheduledThreadPool(10);
        this.activeRooms = new ConcurrentHashMap<>();
        this.roomTimers = new ConcurrentHashMap<>();
    }

    // ============================================
    // PUBLIC API
    // ============================================

    /**
     * Tạo phòng mới
     */
    public GameRoom createRoom(String roomId, String hostId) {
        GameRoom room = GameRoom.builder()
                .roomId(roomId)
                .hostId(hostId)
                .currentState(GameState.WAITING)
                .players(new ConcurrentHashMap<>())
                .build();

        activeRooms.put(roomId, room);
        log.info("Created room: {}", roomId);
        return room;
    }

    /**
     * Lấy phòng theo ID
     */
    public GameRoom getRoom(String roomId) {
        return activeRooms.get(roomId);
    }

    /**
     * Bắt đầu game
     */
    public void startGame(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) {
            log.error("Room not found: {}", roomId);
            return;
        }

        // Kiểm tra điều kiện bắt đầu
//        if (room.getPlayerCount() < 2) {
//            log.warn("Not enough players in room: {}", roomId);
//            broadcastError(roomId, "Cần ít nhất 2 người chơi để bắt đầu");
//            return;
//        }

        // Chuyển sang state BLIND_BET
        room.setCurrentState(GameState.BLIND_BET);
        room.setTimeRemaining(STATE_DURATION.get(GameState.BLIND_BET));

        log.info("Game started in room: {}", roomId);
        broadcastRoomStatus(roomId);

        // Khởi động game loop
        startGameLoop(roomId);
    }



    /**
     * Dừng game
     */
    public void stopGame(String roomId) {
        ScheduledFuture<?> timer = roomTimers.remove(roomId);
        if (timer != null) {
            timer.cancel(false);
            log.info("Game loop stopped for room: {}", roomId);
        }

        GameRoom room = activeRooms.get(roomId);
        if (room != null) {
            room.setCurrentState(GameState.FINISHED);
            broadcastRoomStatus(roomId);
        }
    }

    // ============================================
    // GAME LOOP
    // ============================================

    /**
     * Khởi động game loop - chạy mỗi giây
     */
    private void startGameLoop(String roomId) {
        // Hủy timer cũ nếu có
        ScheduledFuture<?> oldTimer = roomTimers.get(roomId);
        if (oldTimer != null) {
            oldTimer.cancel(false);
        }

        // Tạo timer mới chạy mỗi giây
        ScheduledFuture<?> timer = scheduler.scheduleAtFixedRate(
                () -> runGameLoop(roomId),
                0,
                1,
                TimeUnit.SECONDS
        );

        roomTimers.put(roomId, timer);
    }

    /**
     * Game loop chạy mỗi giây
     */
    private void runGameLoop(String roomId) {
        try {
            GameRoom room = activeRooms.get(roomId);
            if (room == null || room.getCurrentState() == GameState.FINISHED) {
                stopGame(roomId);
                return;
            }

            // Giảm thời gian
            int timeLeft = room.getTimeRemaining() - 1;
            room.setTimeRemaining(timeLeft);

            // Broadcast timer update
            broadcastRoomStatus(roomId);

            // Khi hết thời gian, chuyển state
            if (timeLeft <= 0) {
                handleStateTransition(roomId);
            }

        } catch (Exception e) {
            log.error("Error in game loop for room {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Xử lý chuyển đổi state
     */
    private void handleStateTransition(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        GameState currentState = room.getCurrentState();
        log.info("State transition in room {}: {}", roomId, currentState);

        switch (currentState) {
            case BLIND_BET -> {
                // Chuyển sang phân vai
                room.setCurrentState(GameState.ROLE_ASSIGN);
                room.setTimeRemaining(STATE_DURATION.get(GameState.ROLE_ASSIGN));

                // Gọi logic phân vai
                assignRoles(roomId);
            }

            case ROLE_ASSIGN -> {
                // Chuyển sang thảo luận
                room.setCurrentState(GameState.MARKET_CHAT);
                room.setTimeRemaining(STATE_DURATION.get(GameState.MARKET_CHAT));

                // Load câu hỏi
                loadQuestion(roomId);
            }

            case MARKET_CHAT -> {
                // Chuyển sang chốt đơn
                room.setCurrentState(GameState.CLOSING);
                room.setTimeRemaining(STATE_DURATION.get(GameState.CLOSING));
            }

            case CLOSING -> {
                // Chuyển sang tính tiền
                room.setCurrentState(GameState.CALCULATION);
                room.setTimeRemaining(STATE_DURATION.get(GameState.CALCULATION));

                // Tính toán kết quả (sẽ tích hợp EconomyService sau)
                calculateResults(roomId);
            }

            case CALCULATION -> {
                // Kiểm tra điều kiện kết thúc hoặc tiếp tục vòng mới
                if (shouldEndGame(room)) {
                    room.setCurrentState(GameState.FINISHED);
                    stopGame(roomId);
                } else {
                    // Bắt đầu vòng mới
                    room.setCurrentState(GameState.BLIND_BET);
                    room.setTimeRemaining(STATE_DURATION.get(GameState.BLIND_BET));
                    resetRoundData(room);
                }
            }
        }

        broadcastRoomStatus(roomId);
    }

    // ============================================
    // GAME LOGIC
    // ============================================

    /**
     * Phân vai Oracle và Scammer ngẫu nhiên
     */
    private void assignRoles(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        List<Player> playerList = new ArrayList<>(room.getPlayers().values());
        Collections.shuffle(playerList);

        // Reset tất cả về NORMAL
        playerList.forEach(p -> p.setSecretRole(Player.SecretRole.NORMAL));

        // Chọn 1 Oracle và 1 Scammer ngẫu nhiên
        if (playerList.size() >= 2) {
            playerList.get(0).setSecretRole(Player.SecretRole.ORACLE);
            playerList.get(1).setSecretRole(Player.SecretRole.SCAMMER);

            log.info("Assigned roles - Oracle: {}, Scammer: {}",
                    playerList.get(0).getDisplayName(),
                    playerList.get(1).getDisplayName());
        }

        // Phân vai TRADER/INVESTOR
        for (int i = 0; i < playerList.size(); i++) {
            playerList.get(i).setRole(i % 2 == 0 ? Player.Role.TRADER : Player.Role.INVESTOR);
        }

        broadcastRoleAssignment(roomId);
    }

    /**
     * Load câu hỏi (tạm thời mock data)
     */
    private void loadQuestion(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        // Mock question - sau này sẽ lấy từ database
        Map<String, Object> question = new HashMap<>();
        question.put("id", UUID.randomUUID().toString());
        question.put("question", "Bitcoin sẽ tăng hay giảm trong 24h tới?");
        question.put("options", List.of("A. Tăng mạnh", "B. Tăng nhẹ", "C. Giảm nhẹ", "D. Giảm mạnh"));
        question.put("correctAnswer", "B");

        room.setCurrentQuestion(question);
        log.info("Loaded question for room: {}", roomId);
    }

    /**
     * Tính toán kết quả (placeholder - sẽ tích hợp EconomyService)
     */
    private void calculateResults(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        // TODO: Tích hợp với EconomyService để tính toán tiền thưởng/phạt
        log.info("Calculating results for room: {}", roomId);

        // Tạm thời log kết quả
        room.getPlayers().values().forEach(player -> {
            log.info("Player {}: Answer={}, Cash={}",
                    player.getDisplayName(),
                    player.getSelectedAnswer(),
                    player.getCash());
        });
    }

    /**
     * Kiểm tra điều kiện kết thúc game
     */
    private boolean shouldEndGame(GameRoom room) {
        // Kết thúc nếu chỉ còn 1 người hoặc đã chơi đủ số vòng
        return room.getPlayerCount() <= 1;
    }

    /**
     * Reset dữ liệu cho vòng mới
     */
    private void resetRoundData(GameRoom room) {
        room.getPlayers().values().forEach(player -> {
            player.setBlindBetAmount(0.0);
            player.setSelectedAnswer(null);
            player.setReady(false);
        });
        room.setCurrentQuestion(null);
    }

    // ============================================
    // WEBSOCKET BROADCAST
    // ============================================

    /**
     * Broadcast trạng thái phòng tới tất cả client
     */
    private void broadcastRoomStatus(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", room.getRoomId());
        payload.put("state", room.getCurrentState().name());
        payload.put("timeRemaining", room.getTimeRemaining());
        payload.put("playerCount", room.getPlayerCount());
        payload.put("players", room.getPlayers().values());

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/status",
                (Object) payload
        );
    }

    /**
     * Broadcast thông tin phân vai (chỉ gửi vai trò công khai)
     */
    private void broadcastRoleAssignment(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        // Gửi vai trò bí mật riêng cho từng người
        room.getPlayers().values().forEach(player -> {
            Map<String, Object> privateData = new HashMap<>();
            privateData.put("role", player.getRole().name());
            privateData.put("secretRole", player.getSecretRole().name());

            messagingTemplate.convertAndSendToUser(
                    player.getId(),
                    "/queue/private/role",
                    (Object) privateData
            );
        });
    }

    /**
     * Broadcast thông báo lỗi
     */
    private void broadcastError(String roomId, String message) {
        Map<String, Object> errorPayload = new HashMap<>();
        errorPayload.put("message", message);

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/error",
                (Object) errorPayload
        );
    }
}