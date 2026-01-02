package com.trustmarket.game.controller;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.service.GameEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Cho phép Frontend gọi thoải mái
public class RoomController {

    private final GameEngine gameEngine;

    @PostMapping("/create")
    public ResponseEntity<GameRoom> createRoom(@RequestParam String hostId) {
        // Tạo mã phòng ngẫu nhiên 6 số
        String roomId = String.valueOf((int) (Math.random() * 900000) + 100000);
        GameRoom room = gameEngine.createRoom(hostId, roomId);
        return ResponseEntity.ok(room);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<GameRoom> getRoom(@PathVariable String roomId) {
        GameRoom room = gameEngine.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(room);
    }

    @PostMapping("/{roomId}/start")
    public ResponseEntity<Void> startGame(@PathVariable String roomId) {
        gameEngine.startGame(roomId);
        return ResponseEntity.ok().build();
    }
}