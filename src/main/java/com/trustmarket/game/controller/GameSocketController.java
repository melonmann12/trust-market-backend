package com.trustmarket.game.controller;

import com.trustmarket.game.dto.request.AnswerRequest;
import com.trustmarket.game.dto.request.BetRequest;
import com.trustmarket.game.dto.request.JoinRequest;
import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.model.game.Player;
import com.trustmarket.game.service.GameEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
@Slf4j
public class GameSocketController {

    private final GameEngine gameEngine;
    private final SimpMessagingTemplate messagingTemplate;

    // 1. Người chơi Join phòng
    // Client gửi tới: /app/game/join
    @MessageMapping("/game/join")
    public void joinRoom(@Payload JoinRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        GameRoom room = gameEngine.getRoom(request.getRoomId());

        if (room != null) {
            // Tạo Player mới
            Player newPlayer = Player.builder()
                    .id(sessionId) // Dùng session ID làm ID tạm
                    .displayName(request.getNickname())
                    .avatarUrl(request.getAvatarUrl())
                    .cash(1000.0) // Vốn khởi điểm
                    .isReady(true)
                    .build();

            room.getPlayers().put(sessionId, newPlayer);

            // Broadcast danh sách player mới cho cả phòng
            messagingTemplate.convertAndSend("/topic/room/" + request.getRoomId() + "/players", room.getPlayers().values());
            log.info("Player {} joined room {}", request.getNickname(), request.getRoomId());
        }
    }

    // 2. Xử lý Đặt cược (Giai đoạn BLIND_BET)
    // Client gửi tới: /app/game/bet
    @MessageMapping("/game/bet")
    public void handleBet(@Payload BetRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String playerId = headerAccessor.getSessionId();
        GameRoom room = gameEngine.getRoom(request.getRoomId());

        if (room != null) {
            Player player = room.getPlayers().get(playerId);
            if (player != null) {
                // Cập nhật role và tiền cược
                if ("TRADER".equalsIgnoreCase(request.getRole())) {
                    player.setRole(Player.Role.TRADER);
                } else {
                    player.setRole(Player.Role.INVESTOR);
                }
                player.setBlindBetAmount(request.getAmount());

                // Gửi thông báo riêng cho user đó là đã bet thành công
                // (Thực tế nên broadcast sự kiện "User A đã sẵn sàng" để tạo áp lực)
            }
        }
    }

    // 3. Xử lý Trả lời / Đầu tư (Giai đoạn CLOSING)
    // Client gửi tới: /app/game/answer
    @MessageMapping("/game/answer")
    public void handleAnswer(@Payload AnswerRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String playerId = headerAccessor.getSessionId();
        GameRoom room = gameEngine.getRoom(request.getRoomId());

        if (room != null) {
            Player player = room.getPlayers().get(playerId);
            if (player != null) {
                if (player.getRole() == Player.Role.TRADER) {
                    player.setSelectedAnswer(request.getSelectedAnswer());
                } else {
                    player.setInvestTargetId(request.getTargetTraderId());

                    // Broadcast cập nhật Trust Graph (Biểu đồ tiền) ngay lập tức
                    messagingTemplate.convertAndSend("/topic/room/" + request.getRoomId() + "/trust-update", "Update Graph Data");
                }
            }
        }
    }
}