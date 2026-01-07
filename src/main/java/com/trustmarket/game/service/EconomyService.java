package com.trustmarket.game.service;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.model.game.Player;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EconomyService {

    // 💰 Economy Constants
    private static final double MARKET_CRASH_PENALTY = 0.10;      // 10% loss
    private static final double NORMAL_PROFIT_SHARE = 0.20;       // 20% fee
    private static final double ORACLE_PROFIT_SHARE = 0.70;       // 70% stolen by investors

    @Data
    @AllArgsConstructor
    public static class RoundResult {
        public String playerId;
        public String displayName;
        public double cashBefore;
        public double cashAfter;
        public double profitLoss;
        public String reason;
    }

    // ═══════════════════════════════════════════════════════════
    // 🚨 MARKET CRASH (No Traders scenario)
    // ═══════════════════════════════════════════════════════════
    public List<RoundResult> triggerMarketCrash(GameRoom room) {
        List<RoundResult> results = new ArrayList<>();
        log.warn("🚨 MARKET CRASH in room {}", room.getRoomId());

        for (Player p : room.getPlayers().values()) {
            double cashBefore = p.getCash();
            double penalty = cashBefore * MARKET_CRASH_PENALTY;
            p.setCash(Math.max(0, cashBefore - penalty));

            results.add(new RoundResult(
                    p.getId(),
                    p.getDisplayName(),
                    cashBefore,
                    p.getCash(),
                    -penalty,
                    "🚨 Market Crash (No Traders)"
            ));
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════
    // 💰 MAIN CALCULATION (Called after CLOSING phase)
    // ═══════════════════════════════════════════════════════════
    public List<RoundResult> calculateRoundResult(GameRoom room) {
        List<RoundResult> results = new ArrayList<>();

        if (room == null || room.getCurrentQuestion() == null) {
            log.error("❌ Invalid room or missing question");
            return results;
        }

        String correctAnswer = (String) room.getCurrentQuestion().get("correctAnswer");

        List<Player> traders = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.TRADER)
                .collect(Collectors.toList());

        List<Player> investors = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.INVESTOR)
                .collect(Collectors.toList());

        log.info("💰 Calculating results: {} traders, {} investors",
                traders.size(), investors.size());

        // ──────────────────────────────────────────────────────
        // STEP 1: Process Traders
        // ──────────────────────────────────────────────────────
        Map<String, Boolean> traderEligible = new HashMap<>();

        for (Player trader : traders) {
            double cashBefore = trader.getCash();
            double stake = trader.getBlindBetAmount();

            String traderAnswer = trader.getSelectedAnswer();
            if (traderAnswer == null || traderAnswer.isEmpty()) {
                traderAnswer = "NONE";
            }

            boolean isCorrect = correctAnswer.equalsIgnoreCase(traderAnswer);
            Player.SecretRole role = trader.getSecretRole();

            log.info("🎲 Trader {}: Role={}, Answer={}, Correct={}, Stake={}",
                    trader.getDisplayName(), role, traderAnswer, isCorrect, stake);

            // 🎭 SCAMMER Logic (Wins by being WRONG)
            if (role == Player.SecretRole.SCAMMER) {
                if (isCorrect) {
                    // Scammer answered CORRECTLY → Penalty
                    trader.setCash(Math.max(0, cashBefore - stake));
                    results.add(new RoundResult(
                            trader.getId(),
                            trader.getDisplayName(),
                            cashBefore,
                            trader.getCash(),
                            -stake,
                            "🎭 Scammer answered CORRECTLY (Violated role) → Lost bet"
                    ));
                    traderEligible.put(trader.getId(), false);
                } else {
                    // Scammer answered WRONGLY → No loss (wins condition)
                    results.add(new RoundResult(
                            trader.getId(),
                            trader.getDisplayName(),
                            cashBefore,
                            trader.getCash(),
                            0,
                            "🎭 Scammer answered WRONG (Correct role) → Safe"
                    ));
                    traderEligible.put(trader.getId(), true);
                }
            }
            // 🔮 ORACLE & NORMAL Logic
            else {
                if (isCorrect) {
                    // Correct answer → Win stake
                    trader.setCash(cashBefore + stake);
                    results.add(new RoundResult(
                            trader.getId(),
                            trader.getDisplayName(),
                            cashBefore,
                            trader.getCash(),
                            stake,
                            (role == Player.SecretRole.ORACLE ? "🔮 Oracle" : "📈 Trader")
                                    + " CORRECT (+100% stake)"
                    ));
                    traderEligible.put(trader.getId(), true);
                } else {
                    // Wrong answer → Lose stake
                    trader.setCash(Math.max(0, cashBefore - stake));
                    results.add(new RoundResult(
                            trader.getId(),
                            trader.getDisplayName(),
                            cashBefore,
                            trader.getCash(),
                            -stake,
                            (role == Player.SecretRole.ORACLE ? "🔮 Oracle" : "📉 Trader")
                                    + " WRONG (-100% stake)"
                    ));
                    traderEligible.put(trader.getId(), false);
                }
            }
        }

        // ──────────────────────────────────────────────────────
        // STEP 2: Process Investors
        // ──────────────────────────────────────────────────────
        for (Player trader : traders) {
            String traderId = trader.getId();

            List<Player> myInvestors = investors.stream()
                    .filter(inv -> traderId.equals(inv.getInvestTargetId()))
                    .collect(Collectors.toList());

            if (myInvestors.isEmpty()) continue;

            boolean isWinner = traderEligible.getOrDefault(traderId, false);
            Player.SecretRole role = trader.getSecretRole();

            log.info("💎 Processing {} investors for Trader {} (Winner: {}, Role: {})",
                    myInvestors.size(), trader.getDisplayName(), isWinner, role);

            // 🎭 SCAMMER who won (answered wrong) → STEALS all investor money
            if (role == Player.SecretRole.SCAMMER && isWinner) {
                double stolen = 0;
                for (Player inv : myInvestors) {
                    double amt = inv.getBlindBetAmount();
                    double invCashBefore = inv.getCash();
                    inv.setCash(Math.max(0, invCashBefore - amt));
                    stolen += amt;

                    results.add(new RoundResult(
                            inv.getId(),
                            inv.getDisplayName(),
                            invCashBefore,
                            inv.getCash(),
                            -amt,
                            "😈 Scammed by " + trader.getDisplayName()
                    ));
                }

                double traderCashBefore = trader.getCash();
                trader.setCash(traderCashBefore + stolen);
                results.add(new RoundResult(
                        traderId,
                        trader.getDisplayName(),
                        traderCashBefore,
                        trader.getCash(),
                        stolen,
                        "😈 Stole investor funds"
                ));
            }
            // ✅ NORMAL/ORACLE who won OR SCAMMER who lost → Investors win
            else if (isWinner || (role == Player.SecretRole.SCAMMER && !isWinner)) {
                double feeTotal = 0;

                for (Player inv : myInvestors) {
                    double amt = inv.getBlindBetAmount();
                    double profit = amt;

                    // NORMAL traders take 20% fee
                    if (role == Player.SecretRole.NORMAL) {
                        profit *= (1 - NORMAL_PROFIT_SHARE);
                    }

                    double invCashBefore = inv.getCash();
                    inv.setCash(invCashBefore + profit);

                    results.add(new RoundResult(
                            inv.getId(),
                            inv.getDisplayName(),
                            invCashBefore,
                            inv.getCash(),
                            profit,
                            "💎 Investment succeeded"
                    ));

                    if (role == Player.SecretRole.NORMAL) {
                        feeTotal += (amt * NORMAL_PROFIT_SHARE);
                    }
                }

                // NORMAL traders collect fee
                if (role == Player.SecretRole.NORMAL && feeTotal > 0) {
                    double traderCashBefore = trader.getCash();
                    trader.setCash(traderCashBefore + feeTotal);
                    results.add(new RoundResult(
                            traderId,
                            trader.getDisplayName(),
                            traderCashBefore,
                            trader.getCash(),
                            feeTotal,
                            "💼 Commission from investors"
                    ));
                }
                // ORACLE gets robbed by investors
                else if (role == Player.SecretRole.ORACLE && isWinner) {
                    double penalty = trader.getBlindBetAmount() * ORACLE_PROFIT_SHARE;
                    double traderCashBefore = trader.getCash();
                    trader.setCash(Math.max(0, traderCashBefore - penalty));
                    results.add(new RoundResult(
                            traderId,
                            trader.getDisplayName(),
                            traderCashBefore,
                            trader.getCash(),
                            -penalty,
                            "🔮 Oracle profits stolen by investors"
                    ));
                }
            }
            // ❌ Trader lost (and not Scammer with wrong answer) → Everyone loses
            else {
                for (Player inv : myInvestors) {
                    double amt = inv.getBlindBetAmount();
                    double invCashBefore = inv.getCash();
                    inv.setCash(Math.max(0, invCashBefore - amt));

                    results.add(new RoundResult(
                            inv.getId(),
                            inv.getDisplayName(),
                            invCashBefore,
                            inv.getCash(),
                            -amt,
                            "📉 Trader failed → Lost investment"
                    ));
                }
            }
        }

        log.info("✅ Round calculation complete. {} results generated.", results.size());
        return results;
    }
}