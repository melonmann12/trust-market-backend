package com.trustmarket.game.service;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.model.game.GameState;
import com.trustmarket.game.model.game.Player;
import com.trustmarket.game.model.game.Question;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GameEngine {

    private final SimpMessagingTemplate messagingTemplate;
    private final EconomyService economyService;
    private final AIService aiService;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService asyncExecutor;
    private final ConcurrentHashMap<String, GameRoom> activeRooms;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> roomTimers;

    // ⚙️ DEBUG MODE: Set to true for single-player testing
    private static final boolean DEBUG_MODE = true;

    private static final Map<GameState, Integer> STATE_DURATION = Map.of(
            GameState.BLIND_BET, 20,
            GameState.ROLE_ASSIGN, 5,
            GameState.MARKET_CHAT, 45,
            GameState.CLOSING, 10,
            GameState.CALCULATION, 15
    );

    public GameEngine(
            SimpMessagingTemplate messagingTemplate,
            EconomyService economyService,
            AIService aiService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.economyService = economyService;
        this.aiService = aiService;
        this.scheduler = Executors.newScheduledThreadPool(10);
        this.asyncExecutor = Executors.newFixedThreadPool(5);
        this.activeRooms = new ConcurrentHashMap<>();
        this.roomTimers = new ConcurrentHashMap<>();
    }

    // ═══════════════════════════════════════════════════════════
    // 📡 API METHODS
    // ═══════════════════════════════════════════════════════════

    public GameRoom createRoom(String roomId, String hostId) {
        if (activeRooms.containsKey(roomId)) {
            log.warn("⚠️ Room {} already exists. Stopping old instance.", roomId);
            stopGame(roomId);
        }

        GameRoom room = GameRoom.builder()
                .roomId(roomId)
                .hostId(hostId)
                .currentState(GameState.WAITING)
                .currentRound(1)
                .totalRounds(10)
                .players(new ConcurrentHashMap<>())
                .build();

        Player host = Player.builder()
                .id(hostId)
                .displayName(hostId)
                .cash(2000.0)
                .build();

        room.getPlayers().put(hostId, host);
        activeRooms.put(roomId, room);

        log.info("✅ Room {} created by host {}", roomId, hostId);
        return room;
    }

    public void joinRoom(String roomId, String playerId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) {
            throw new RuntimeException("Room does not exist!");
        }

        if (room.getPlayers().containsKey(playerId)) {
            log.info("ℹ️ Player {} already in room {}", playerId, roomId);
            return;
        }

        Player p = Player.builder()
                .id(playerId)
                .displayName(playerId)
                .cash(2000.0)
                .build();

        room.getPlayers().put(playerId, p);
        log.info("✅ Player {} joined room {}. Total players: {}",
                playerId, roomId, room.getPlayerCount());

        broadcastRoomStatus(roomId);
    }

    public void startGame(String roomId, String requesterId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) {
            throw new RuntimeException("Room not found");
        }

        if (!room.getHostId().equals(requesterId)) {
            throw new RuntimeException("Only host can start the game");
        }

        room.setCurrentRound(1);
        log.info("🎮 Game starting in room {}. Players: {}",
                roomId, room.getPlayerCount());

        startPhase(roomId, GameState.BLIND_BET);
        startGameLoop(roomId);
    }

    public void handleBet(String roomId, String playerId, double amount) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        Player p = room.getPlayers().get(playerId);
        if (p == null) return;

        if (amount > p.getCash()) {
            log.warn("⚠️ Player {} tried to bet {} but only has {}",
                    playerId, amount, p.getCash());
            amount = p.getCash();
        }

        p.setBlindBetAmount(amount);
        log.info("💰 Player {} bet {} in room {}", playerId, amount, roomId);
    }

    // 🔧 FIXED: Now properly updates player state and broadcasts
    public void playerSelectRole(String roomId, String playerId, String roleStr) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) {
            log.error("❌ Room {} not found", roomId);
            throw new RuntimeException("Room not found");
        }

        Player p = room.getPlayers().get(playerId);
        if (p == null) {
            log.error("❌ Player {} not found in room {}", playerId, roomId);
            throw new RuntimeException("Player not found");
        }

        try {
            Player.Role role = Player.Role.valueOf(roleStr.toUpperCase());
            p.setRole(role);
            p.setReady(true);

            log.info("✅ Player {} selected role {} in room {}", playerId, role, roomId);

            messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/private",
                    (Object) Map.of("role", roleStr, "status", "confirmed")  // Add (Object) cast
            );

            // Broadcast updated room state
            broadcastRoomStatus(roomId);

        } catch (IllegalArgumentException e) {
            log.error("❌ Invalid role: {}", roleStr);
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    public void handleInvest(String roomId, String investorId, String targetTraderId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        Player investor = room.getPlayers().get(investorId);
        Player trader = room.getPlayers().get(targetTraderId);

        if (investor == null || trader == null) {
            log.error("❌ Invalid investor or trader");
            return;
        }

        if (investor.getRole() != Player.Role.INVESTOR) {
            log.error("❌ Player {} is not an investor", investorId);
            return;
        }

        if (trader.getRole() != Player.Role.TRADER) {
            log.error("❌ Player {} is not a trader", targetTraderId);
            return;
        }

        investor.setInvestTargetId(targetTraderId);
        log.info("💎 Investor {} → Trader {} in room {}",
                investorId, targetTraderId, roomId);

        // Line ~145: handleInvest method
        messagingTemplate.convertAndSend(
                "/topic/game/" + roomId + "/trust-update",
                (Object) Map.of("investor", investorId, "trader", targetTraderId)  // Add cast
        );
    }

    public void submitAnswer(String roomId, String playerId, String answer) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        Player p = room.getPlayers().get(playerId);
        if (p == null || p.getRole() != Player.Role.TRADER) {
            log.error("❌ Invalid answer submission from {}", playerId);
            return;
        }

        p.setSelectedAnswer(answer.toUpperCase());
        log.info("📝 Trader {} answered: {}", playerId, answer);
    }

    public GameRoom getRoom(String roomId) {
        return activeRooms.get(roomId);
    }

    // ═══════════════════════════════════════════════════════════
    // 🔄 GAME LOOP
    // ═══════════════════════════════════════════════════════════

    private void startGameLoop(String roomId) {
        if (roomTimers.containsKey(roomId)) {
            log.warn("⚠️ Timer already running for room {}", roomId);
            return;
        }

        ScheduledFuture<?> timer = scheduler.scheduleAtFixedRate(() -> {
            try {
                GameRoom room = activeRooms.get(roomId);
                if (room == null || room.getCurrentState() == GameState.FINISHED) {
                    stopGame(roomId);
                    return;
                }

                room.setTimeRemaining(room.getTimeRemaining() - 1);

                // Broadcast every second
                broadcastRoomStatus(roomId);

                // Transition when time expires
                if (room.getTimeRemaining() <= 0) {
                    nextPhase(roomId);
                }

            } catch (Exception e) {
                log.error("❌ Game loop error in room {}: {}", roomId, e.getMessage(), e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        roomTimers.put(roomId, timer);
        log.info("⏱️ Timer started for room {}", roomId);
    }

    // 🔧 FIXED: Non-blocking AI call with proper state transition
    private void startPhase(String roomId, GameState state) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        room.setCurrentState(state);
        room.setTimeRemaining(STATE_DURATION.getOrDefault(state, 30));

        log.info("🔄 Room {} → Phase: {} ({}s)", roomId, state, room.getTimeRemaining());

        // Immediate broadcast
        broadcastRoomStatus(roomId);

        // 🚀 Async AI call for MARKET_CHAT
        if (state == GameState.MARKET_CHAT) {
            CompletableFuture.runAsync(() -> {
                try {
                    log.info("🤖 Generating question for room {}...", roomId);
                    loadQuestion(roomId);
                    broadcastRoomStatus(roomId);
                    log.info("✅ Question loaded for room {}", roomId);
                } catch (Exception e) {
                    log.error("❌ AI generation failed: {}", e.getMessage(), e);
                }
            }, asyncExecutor);
        }
    }

    private void nextPhase(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        log.info("➡️ Room {} transitioning from {}", roomId, room.getCurrentState());

        switch (room.getCurrentState()) {
            case BLIND_BET -> handleBlindBetEnd(roomId);
            case ROLE_ASSIGN -> startPhase(roomId, GameState.MARKET_CHAT);
            case MARKET_CHAT -> startPhase(roomId, GameState.CLOSING);
            case CLOSING -> handleClosingEnd(roomId);
            case CALCULATION -> handleCalculationEnd(roomId);
        }
    }

    // 🔧 FIXED: Better trader count validation
    private void handleBlindBetEnd(String roomId) {
        GameRoom room = activeRooms.get(roomId);

        List<Player> traders = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.TRADER)
                .collect(Collectors.toList());

        log.info("📊 Room {} has {} traders", roomId, traders.size());

        // Market Crash condition
        if (traders.isEmpty() && room.getPlayerCount() > 1) {
            log.warn("🚨 MARKET CRASH in room {} - No traders!", roomId);
            economyService.triggerMarketCrash(room);
            broadcastError(roomId, "🚨 MARKET CRASH! No traders selected.");

            // Reset and start new round
            resetRoundData(room);
            startPhase(roomId, GameState.BLIND_BET);
            return;
        }

        // Normal flow
        assignRoles(roomId);
        startPhase(roomId, GameState.ROLE_ASSIGN);
    }

    private void handleClosingEnd(String roomId) {
        startPhase(roomId, GameState.CALCULATION);

        // Calculate in background to avoid blocking
        CompletableFuture.runAsync(() -> {
            calculateResults(roomId);
        }, asyncExecutor);
    }

    private void handleCalculationEnd(String roomId) {
        GameRoom room = activeRooms.get(roomId);

        if (room.getCurrentRound() >= room.getTotalRounds()) {
            room.setCurrentState(GameState.FINISHED);
            log.info("🏁 Game finished in room {}", roomId);
            broadcastRoomStatus(roomId);
        } else {
            room.setCurrentRound(room.getCurrentRound() + 1);
            log.info("🔄 Starting round {}/{} in room {}",
                    room.getCurrentRound(), room.getTotalRounds(), roomId);
            resetRoundData(room);
            startPhase(roomId, GameState.BLIND_BET);
        }
    }

    private void assignRoles(String roomId) {
        GameRoom room = activeRooms.get(roomId);

        List<Player> traders = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.TRADER)
                .collect(Collectors.toList());

        // Reset all to NORMAL
        traders.forEach(t -> t.setSecretRole(Player.SecretRole.NORMAL));

        // 🎭 Debug Mode: Allow single player testing
        if (DEBUG_MODE && traders.size() == 1) {
            Player solo = traders.get(0);
            // Randomly make them Oracle or Scammer for testing
            Player.SecretRole debugRole = Math.random() > 0.5
                    ? Player.SecretRole.ORACLE
                    : Player.SecretRole.SCAMMER;
            solo.setSecretRole(debugRole);
            log.info("🐛 DEBUG MODE: Solo player {} assigned role {}",
                    solo.getId(), debugRole);
        }
        // Normal mode: Need at least 2 traders
        else if (traders.size() >= 2) {
            Collections.shuffle(traders);
            traders.get(0).setSecretRole(Player.SecretRole.ORACLE);
            traders.get(1).setSecretRole(Player.SecretRole.SCAMMER);
            log.info("🎭 Roles assigned: Oracle={}, Scammer={}",
                    traders.get(0).getId(), traders.get(1).getId());
        }

        // --- 👇 ĐOẠN SỬA LỖI Ở ĐÂY (Thêm vòng lặp forEach) 👇 ---
        traders.forEach(t -> {
            messagingTemplate.convertAndSendToUser(
                    t.getId(),
                    "/queue/private/role",
                    (Object) Map.of("secretRole", t.getSecretRole().toString())  // Add cast
            );
        });
        // --------------------------------------------------------

        // Broadcast public trader list
        List<Map<String, String>> publicTraders = traders.stream()
                .map(t -> Map.of("id", t.getId(), "displayName", t.getDisplayName()))
                .collect(Collectors.toList());

        messagingTemplate.convertAndSend(
                "/topic/game/" + roomId + "/traders",
                (Object) Map.of("traders", publicTraders)  // Add cast
        );
    }

    private void loadQuestion(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        try {
            Question q = aiService.generateQuestion("Kinh tế & Lừa đảo");

            Map<String, Object> qMap = new HashMap<>();
            qMap.put("id", q.getId());
            qMap.put("question", q.getContent());
            qMap.put("options", q.getOptions());
            qMap.put("correctAnswer", q.getCorrectAnswer());

            room.setCurrentQuestion(qMap);
            log.info("✅ Question set for room {}", roomId);

        } catch (Exception e) {
            log.error("❌ Failed to load question: {}", e.getMessage(), e);
        }
    }

    private void calculateResults(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room == null) return;

        List<EconomyService.RoundResult> results = economyService.calculateRoundResult(room);

        Map<String, Object> payload = new HashMap<>();
        payload.put("results", results);

        if (room.getCurrentQuestion() != null) {
            payload.put("correctAnswer", room.getCurrentQuestion().get("correctAnswer"));
        }

        // Line ~380: calculateResults method
        messagingTemplate.convertAndSend(
                "/topic/game/" + roomId + "/results",
                (Object) payload  // Add cast
        );


        log.info("💰 Results calculated for room {}. {} entries.",
                roomId, results.size());
    }

    private void resetRoundData(GameRoom room) {
        room.getPlayers().values().forEach(p -> {
            p.setRole(null);
            p.setReady(false);
            p.setSelectedAnswer(null);
            p.setInvestTargetId(null);
            p.setBlindBetAmount(0);
        });
        room.setCurrentQuestion(null);
        log.info("🔄 Round data reset for room {}", room.getRoomId());
    }

    public void stopGame(String roomId) {
        ScheduledFuture<?> timer = roomTimers.remove(roomId);
        if (timer != null) {
            timer.cancel(true);
            log.info("⏹️ Timer stopped for room {}", roomId);
        }

        GameRoom room = activeRooms.get(roomId);
        if (room != null) {
            room.setCurrentState(GameState.FINISHED);
        }
    }

    private void broadcastRoomStatus(String roomId) {
        GameRoom room = activeRooms.get(roomId);
        if (room != null) {
            messagingTemplate.convertAndSend("/topic/game/" + roomId, room);
        }
    }

    private void broadcastError(String roomId, String msg) {
        messagingTemplate.convertAndSend(
                "/topic/game/" + roomId + "/error",
                (Object) Map.of("message", msg)  // Add cast
        );
    }
}