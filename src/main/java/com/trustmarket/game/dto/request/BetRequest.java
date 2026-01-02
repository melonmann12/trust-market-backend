package com.trustmarket.game.dto.request;
import lombok.Data;

@Data
public class BetRequest {
    private String roomId;
    private String role; // "TRADER" hoặc "INVESTOR"
    private double amount;
}