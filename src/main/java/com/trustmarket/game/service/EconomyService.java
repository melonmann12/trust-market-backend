package com.trustmarket.game.service;

import com.trustmarket.game.model.game.GameRoom;
import com.trustmarket.game.model.game.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EconomyService {

    // Các hằng số cấu hình
    private static final double MARKET_CRASH_PENALTY = 0.10;  // 10% penalty khi sập sàn
    private static final double NORMAL_PROFIT_SHARE = 0.20;   // 20% lãi chia cho Trader Normal
    private static final double ORACLE_PROFIT_SHARE = 0.70;   // 70% lãi Oracle chia cho Investor
    private static final double SCAMMER_PROFIT_SHARE = 1.00;  // 100% lãi khi Scammer lừa thành công

    /**
     * DTO để trả về kết quả tính toán
     */
    public static class RoundResult {
        public String playerId;
        public String displayName;
        public double cashBefore;
        public double cashAfter;
        public double profitLoss;
        public String reason;

        public RoundResult(String playerId, String displayName, double cashBefore,
                           double cashAfter, String reason) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.cashBefore = cashBefore;
            this.cashAfter = cashAfter;
            this.profitLoss = cashAfter - cashBefore;
            this.reason = reason;
        }
    }

    /**
     * Tính toán kết quả vòng chơi
     */
    public List<RoundResult> calculateRoundResult(GameRoom room) {
        List<RoundResult> results = new ArrayList<>();

        if (room == null || room.getCurrentQuestion() == null) {
            log.error("Invalid room or missing question");
            return results;
        }

        // Lấy đáp án đúng
        String correctAnswer = (String) room.getCurrentQuestion().get("correctAnswer");

        // Phân loại người chơi
        List<Player> traders = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.TRADER)
                .collect(Collectors.toList());

        List<Player> investors = room.getPlayers().values().stream()
                .filter(p -> p.getRole() == Player.Role.INVESTOR)
                .collect(Collectors.toList());

        // ============================================
        // BƯỚC 1: CHECK SẬP SÀN
        // ============================================
        if (traders.isEmpty()) {
            log.warn("Market crash! No traders in room {}", room.getRoomId());
            applyMarketCrash(room, results);
            return results;
        }

        // ============================================
        // BƯỚC 2: TÍNH LÃI/LỖ CHO TRADERS
        // ============================================
        Map<String, Boolean> traderCorrectness = new HashMap<>();

        for (Player trader : traders) {
            double cashBefore = trader.getCash();
            boolean isCorrect = correctAnswer.equals(trader.getSelectedAnswer());
            traderCorrectness.put(trader.getId(), isCorrect);

            if (isCorrect) {
                // Trader đúng: Lãi = số tiền cược
                double profit = trader.getBlindBetAmount();
                trader.setCash(trader.getCash() + profit);

                results.add(new RoundResult(
                        trader.getId(),
                        trader.getDisplayName(),
                        cashBefore,
                        trader.getCash(),
                        "Trader đúng (+100% cược)"
                ));

                log.info("Trader {} correct: +{}", trader.getDisplayName(), profit);
            } else {
                // Trader sai: Lỗ = số tiền cược
                double loss = trader.getBlindBetAmount();
                trader.setCash(trader.getCash() - loss);

                results.add(new RoundResult(
                        trader.getId(),
                        trader.getDisplayName(),
                        cashBefore,
                        trader.getCash(),
                        "Trader sai (-100% cược)"
                ));

                log.info("Trader {} wrong: -{}", trader.getDisplayName(), loss);
            }
        }

        // ============================================
        // BƯỚC 3: CHIA CHÁC (TRÁI TIM CỦA GAME)
        // ============================================
        for (Player trader : traders) {
            boolean traderCorrect = traderCorrectness.get(trader.getId());
            Player.SecretRole secretRole = trader.getSecretRole();

            // Lấy danh sách Investor đã đặt vào Trader này
            List<Player> investorsForThisTrader = investors.stream()
                    .filter(inv -> trader.getId().equals(inv.getSelectedAnswer()))
                    .collect(Collectors.toList());

            if (investorsForThisTrader.isEmpty()) {
                continue;
            }

            switch (secretRole) {
                case NORMAL -> handleNormalTrader(trader, investorsForThisTrader, traderCorrect, results);
                case ORACLE -> handleOracleTrader(trader, investorsForThisTrader, traderCorrect, results);
                case SCAMMER -> handleScammerTrader(trader, investorsForThisTrader, traderCorrect, results);
            }
        }

        // Tính cho các Investor không đặt vào ai hoặc đặt vào Trader không tồn tại
        for (Player investor : investors) {
            if (results.stream().noneMatch(r -> r.playerId.equals(investor.getId()))) {
                results.add(new RoundResult(
                        investor.getId(),
                        investor.getDisplayName(),
                        investor.getCash(),
                        investor.getCash(),
                        "Không tham gia đầu tư"
                ));
            }
        }

        return results;
    }

    // ============================================
    // PRIVATE HELPER METHODS
    // ============================================

    /**
     * Xử lý sập sàn: Trừ 10% tổng tài sản mọi người
     */
    private void applyMarketCrash(GameRoom room, List<RoundResult> results) {
        for (Player player : room.getPlayers().values()) {
            double cashBefore = player.getCash();
            double penalty = cashBefore * MARKET_CRASH_PENALTY;
            player.setCash(cashBefore - penalty);

            results.add(new RoundResult(
                    player.getId(),
                    player.getDisplayName(),
                    cashBefore,
                    player.getCash(),
                    "Sập sàn! (-10% tài sản)"
            ));

            log.info("Market crash penalty for {}: -{}", player.getDisplayName(), penalty);
        }
    }

    /**
     * Xử lý Trader NORMAL: Chia 20% lãi từ Investor
     */
    private void handleNormalTrader(Player trader, List<Player> investors,
                                    boolean traderCorrect, List<RoundResult> results) {
        if (!traderCorrect) {
            // Trader sai thì Investor không mất gì thêm
            for (Player investor : investors) {
                results.add(new RoundResult(
                        investor.getId(),
                        investor.getDisplayName(),
                        investor.getCash(),
                        investor.getCash(),
                        "Trader Normal sai - Giữ nguyên vốn"
                ));
            }
            return;
        }

        // Trader đúng: Investor lãi, chia 20% cho Trader
        double totalTraderBonus = 0;

        for (Player investor : investors) {
            double cashBefore = investor.getCash();
            double investAmount = investor.getBlindBetAmount();
            double investorProfit = investAmount; // Lãi 100% số cược

            // Trích 20% lãi chia cho Trader
            double shareToTrader = investorProfit * NORMAL_PROFIT_SHARE;
            double investorNetProfit = investorProfit - shareToTrader;

            investor.setCash(cashBefore + investorNetProfit);
            totalTraderBonus += shareToTrader;

            results.add(new RoundResult(
                    investor.getId(),
                    investor.getDisplayName(),
                    cashBefore,
                    investor.getCash(),
                    String.format("Trader Normal đúng (+%.0f%%, chia 20%% cho Trader)",
                            (investorNetProfit / investAmount) * 100)
            ));

            log.info("Investor {} profit from Normal Trader: +{} (shared {} to Trader)",
                    investor.getDisplayName(), investorNetProfit, shareToTrader);
        }

        // Cộng bonus vào Trader
        final double traderCashBefore = trader.getCash();
        trader.setCash(trader.getCash() + totalTraderBonus);
        final double traderCashAfter = trader.getCash();
        final double bonusAmount = totalTraderBonus;

        // Cập nhật result của Trader
        results.stream()
                .filter(r -> r.playerId.equals(trader.getId()))
                .findFirst()
                .ifPresent(r -> {
                    r.cashAfter = traderCashAfter;
                    r.profitLoss = traderCashAfter - traderCashBefore;
                    r.reason += String.format(" + %.2f từ Investor", bonusAmount);
                });

        log.info("Trader Normal {} received bonus: +{}", trader.getDisplayName(), totalTraderBonus);
    }

    /**
     * Xử lý Trader ORACLE: Chia 70% lãi Oracle cho Investor
     */
    private void handleOracleTrader(Player trader, List<Player> investors,
                                    boolean traderCorrect, List<RoundResult> results) {
        if (!traderCorrect) {
            // Oracle không thể sai (theo lý thuyết), nhưng xử lý cho chắc
            for (Player investor : investors) {
                results.add(new RoundResult(
                        investor.getId(),
                        investor.getDisplayName(),
                        investor.getCash(),
                        investor.getCash(),
                        "Oracle sai (bất thường) - Giữ nguyên vốn"
                ));
            }
            return;
        }

        // Oracle đúng: Lấy 70% lãi Oracle chia đều cho Investor
        double oracleProfit = trader.getBlindBetAmount(); // Lãi của Oracle
        double sharePool = oracleProfit * ORACLE_PROFIT_SHARE;
        double sharePerInvestor = investors.isEmpty() ? 0 : sharePool / investors.size();

        // Trừ tiền từ Oracle
        final double traderCashBefore = trader.getCash();
        trader.setCash(trader.getCash() - sharePool);
        final double traderCashAfter = trader.getCash();
        final double sharedAmount = sharePool;

        // Cập nhật result của Oracle
        results.stream()
                .filter(r -> r.playerId.equals(trader.getId()))
                .findFirst()
                .ifPresent(r -> {
                    r.cashAfter = traderCashAfter;
                    r.profitLoss = traderCashAfter - traderCashBefore;
                    r.reason += String.format(" - %.2f chia cho Investor", sharedAmount);
                });

        // Chia cho Investor
        for (Player investor : investors) {
            double cashBefore = investor.getCash();
            double investorProfit = investor.getBlindBetAmount() + sharePerInvestor;

            investor.setCash(cashBefore + investorProfit);

            results.add(new RoundResult(
                    investor.getId(),
                    investor.getDisplayName(),
                    cashBefore,
                    investor.getCash(),
                    String.format("Oracle đúng (+100%% + %.2f từ Oracle)", sharePerInvestor)
            ));

            log.info("Investor {} profit from Oracle: +{} (includes {} Oracle share)",
                    investor.getDisplayName(), investorProfit, sharePerInvestor);
        }

        log.info("Oracle {} shared profit: -{}", trader.getDisplayName(), sharePool);
    }

    /**
     * Xử lý Trader SCAMMER: Nếu lừa thành công, ăn trọn 100% tiền Investor
     */
    private void handleScammerTrader(Player trader, List<Player> investors,
                                     boolean traderCorrect, List<RoundResult> results) {
        if (traderCorrect) {
            // Scammer đúng thì Investor lãi bình thường
            for (Player investor : investors) {
                double cashBefore = investor.getCash();
                double profit = investor.getBlindBetAmount();
                investor.setCash(cashBefore + profit);

                results.add(new RoundResult(
                        investor.getId(),
                        investor.getDisplayName(),
                        cashBefore,
                        investor.getCash(),
                        "Scammer đúng (may mắn) - Lãi 100%"
                ));

                log.info("Investor {} lucky profit from Scammer: +{}",
                        investor.getDisplayName(), profit);
            }
            return;
        }

        // Scammer lừa thành công: Ăn trọn 100% tiền cược của Investor
        double totalStolen = 0;

        for (Player investor : investors) {
            double cashBefore = investor.getCash();
            double investAmount = investor.getBlindBetAmount();

            // Investor mất toàn bộ số tiền cược
            investor.setCash(cashBefore - investAmount);
            totalStolen += investAmount;

            results.add(new RoundResult(
                    investor.getId(),
                    investor.getDisplayName(),
                    cashBefore,
                    investor.getCash(),
                    "Bị Scammer lừa (-100% cược)"
            ));

            log.info("Investor {} scammed: -{}", investor.getDisplayName(), investAmount);
        }

        // Cộng tiền vào Scammer
        final double traderCashBefore = trader.getCash();
        trader.setCash(trader.getCash() + totalStolen);
        final double traderCashAfter = trader.getCash();
        final double stolenAmount = totalStolen;

        // Cập nhật result của Scammer
        results.stream()
                .filter(r -> r.playerId.equals(trader.getId()))
                .findFirst()
                .ifPresent(r -> {
                    r.cashAfter = traderCashAfter;
                    r.profitLoss = traderCashAfter - traderCashBefore;
                    r.reason += String.format(" + %.2f từ lừa đảo", stolenAmount);
                });

        log.info("Scammer {} stole: +{}", trader.getDisplayName(), totalStolen);
    }
}