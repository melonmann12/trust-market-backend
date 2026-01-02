package com.trustmarket.game.dto.request;
import lombok.Data;

@Data
public class AnswerRequest {
    private String roomId;
    private String selectedAnswer; // "A", "B", "C", "D" (Dành cho Trader)
    private String targetTraderId; // ID người mình muốn đầu tư (Dành cho Investor)
}