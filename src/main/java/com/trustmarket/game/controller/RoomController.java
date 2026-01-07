package com.trustmarket.game.controller;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.service.GameEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final GameEngine gameEngine;

    // 1. Create Room
    @PostMapping("/create")
    public ResponseEntity<GameRoom> createRoom(
            @RequestParam String roomId,
            @RequestParam String hostId
    ) {
        log.info("🏠 Creating room: {} by host: {}", roomId, hostId);
        GameRoom room = gameEngine.createRoom(roomId, hostId);
        return ResponseEntity.ok(room);
    }

    // 2. Join Room
    @PostMapping("/{roomId}/join")
    public ResponseEntity<?> joinRoom(
            @PathVariable String roomId,
            @RequestParam String playerId
    ) {
        try {
            log.info("👤 Player {} joining room {}", playerId, roomId);
            gameEngine.joinRoom(roomId, playerId);
            return ResponseEntity.ok(Map.of(
                    "message", "Joined success",
                    "roomId", roomId,
                    "playerId", playerId
            ));
        } catch (Exception e) {
            log.error("❌ Join failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 3. Get Room Info
    @GetMapping("/{roomId}")
    public ResponseEntity<GameRoom> getRoom(@PathVariable String roomId) {
        GameRoom room = gameEngine.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(room);
    }

    // 4. Start Game
    @PostMapping("/{roomId}/start")
    public ResponseEntity<?> startGame(
            @PathVariable String roomId,
            @RequestParam String playerId
    ) {
        try {
            log.info("🎮 Player {} requesting to start game in room {}", playerId, roomId);
            gameEngine.startGame(roomId, playerId);
            return ResponseEntity.ok(Map.of("message", "Game started"));
        } catch (RuntimeException e) {
            log.error("❌ Start game failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 5. Choose Role (FIXED: Now properly logs and validates)
    @PostMapping("/{roomId}/choose-role")
    public ResponseEntity<?> chooseRole(
            @PathVariable String roomId,
            @RequestBody Map<String, String> payload
    ) {
        String playerId = payload.get("playerId");
        String role = payload.get("role");

        log.info("🎭 Player {} choosing role {} in room {}", playerId, role, roomId);

        try {
            gameEngine.playerSelectRole(roomId, playerId, role);
            log.info("✅ Role selection successful for player {}", playerId);
            return ResponseEntity.ok(Map.of(
                    "message", "Role selected: " + role,
                    "playerId", playerId,
                    "role", role
            ));
        } catch (Exception e) {
            log.error("❌ Role selection failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 6. Place Bet (NEW: Separated from role selection)
    @PostMapping("/{roomId}/bet")
    public ResponseEntity<?> placeBet(
            @PathVariable String roomId,
            @RequestBody Map<String, Object> payload
    ) {
        String playerId = (String) payload.get("playerId");
        Double amount = ((Number) payload.get("amount")).doubleValue();

        log.info("💰 Player {} betting {} in room {}", playerId, amount, roomId);

        try {
            gameEngine.handleBet(roomId, playerId, amount);
            return ResponseEntity.ok(Map.of(
                    "message", "Bet placed",
                    "amount", amount
            ));
        } catch (Exception e) {
            log.error("❌ Bet failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 7. Invest (Investor chooses Trader)
    @PostMapping("/{roomId}/invest")
    public ResponseEntity<?> invest(
            @PathVariable String roomId,
            @RequestBody Map<String, String> payload
    ) {
        String playerId = payload.get("playerId");
        String targetId = payload.get("targetId");

        log.info("💎 Investor {} targeting Trader {} in room {}", playerId, targetId, roomId);

        try {
            gameEngine.handleInvest(roomId, playerId, targetId);
            return ResponseEntity.ok(Map.of(
                    "message", "Investment placed",
                    "investor", playerId,
                    "trader", targetId
            ));
        } catch (Exception e) {
            log.error("❌ Investment failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 8. Submit Answer (Trader answers question)
    @PostMapping("/{roomId}/submit")
    public ResponseEntity<?> submitAnswer(
            @PathVariable String roomId,
            @RequestBody Map<String, String> payload
    ) {
        String playerId = payload.get("playerId");
        String answer = payload.get("answer");

        log.info("📝 Player {} submitting answer {} in room {}", playerId, answer, roomId);

        try {
            gameEngine.submitAnswer(roomId, playerId, answer);
            return ResponseEntity.ok(Map.of(
                    "message", "Answer submitted",
                    "answer", answer
            ));
        } catch (Exception e) {
            log.error("❌ Answer submission failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}