package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator.Evidence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Closed-trade statistics and unclosed exposure are deliberately separate. Rates are decimals. */
public record UserStrategyBacktestResult(UserStrategyBacktestRequest execution, String status, int candleCount,
                                        int warmupBars, int requiredWarmupBars, LocalDate actualStartDate,
                                        LocalDate actualEndDate, Metrics metrics, List<Trade> trades,
                                        OpenPosition openPosition, PendingOrder pendingOrder, List<String> assumptions) {
    public UserStrategyBacktestResult {
        trades = List.copyOf(trades);
        assumptions = List.copyOf(assumptions);
    }

    public record Fill(long signalTimestamp, long executionTimestamp, long signalBarTimestamp,
                       long executionBarTimestamp, BigDecimal price, String reason, List<Evidence> evidence) {
        public Fill { evidence = List.copyOf(evidence); }
    }
    public record Trade(Fill entry, Fill exit, int holdingBars, BigDecimal returnRate) { }
    public record OpenPosition(Fill entry, long valuationTimestamp, BigDecimal valuationPrice,
                               int holdingBars, BigDecimal unrealizedReturnRate) { }
    public record PendingOrder(String action, long signalTimestamp, long signalBarTimestamp,
                               String reason, List<Evidence> evidence, String status) {
        public PendingOrder { evidence = List.copyOf(evidence); }
    }
    public record Metrics(int closedTrades, BigDecimal winRate, BigDecimal averageTradeReturnRate,
                          BigDecimal sumTradeReturnRate, BigDecimal tradeReturnMaxDrawdown,
                          BigDecimal tradeSharpeRatio, double averageHoldingBars) {
        public static Metrics from(String symbol, List<Trade> trades) {
            // Reuse the established calculations; expose only accurately named trade-level fields.
            List<BacktestTrade> legacy = trades.stream().map(t -> new BacktestTrade(0, t.holdingBars(),
                    t.entry().price(), t.exit().price(), t.returnRate(), t.holdingBars(), t.exit().reason())).toList();
            BacktestResult result = BacktestResult.from(symbol, null, legacy);
            return new Metrics(result.getTotalTrades(), result.getWinRate(), result.getAvgReturnRate(),
                    result.getCumulativeReturn(), result.getMaxDrawdown(), result.getSharpeRatio(), result.getAvgHoldingDays());
        }
    }
}
