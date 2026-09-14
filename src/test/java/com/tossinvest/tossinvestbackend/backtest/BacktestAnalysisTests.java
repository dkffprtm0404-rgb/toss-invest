package com.tossinvest.tossinvestbackend.backtest;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class BacktestAnalysisTests {
    @Test void monthBoundaryUsesKoreaAndCurveRetainsEveryTradeAtRepeatedTimestamps() throws Exception {
        var trades = List.of(trade("2025-01-31T14:30:00Z", ".10"),
                trade("2025-01-31T15:30:00Z", "-.20"), trade("2025-01-31T15:30:00Z", ".05"));
        var request = UserStrategyBacktestEngineTests.request(UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE, null);
        var result = new UserStrategyBacktestResult(request, "COMPLETED", 0, 0, 0, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 2, 1), Metrics.from("005930", trades), trades, null, null, List.of());
        var snapshot = new SavedBacktestService.Snapshot(1, "test", Instant.EPOCH, null, request,
                new SavedBacktestService.DataSnapshot("test", "1d", "Asia/Seoul", "hash", List.of()), null, result, null);
        var analysis = BacktestAnalysis.from(new SavedBacktestService.RunDetail(1, Instant.EPOCH, "COMPLETED", snapshot));
        assertThat(analysis.curve()).hasSize(4);
        assertThat(analysis.curve().get(3).sumReturnRate()).isEqualByComparingTo("-.05");
        assertThat(analysis.curve().get(2).drawdown()).isEqualByComparingTo("-.20");
        assertThat(analysis.curve().get(3).drawdown()).isEqualByComparingTo("-.15");
        assertThat(analysis.months()).extracting(BacktestAnalysis.Group::key).containsExactly("2025-01", "2025-02");
        assertThat(analysis.months().get(0).sumReturnRate()).isEqualByComparingTo(".10");
        assertThat(analysis.months().get(1).sumReturnRate()).isEqualByComparingTo("-.15");
        assertThat(analysis.months().get(1).winRate()).isEqualByComparingTo(".5");
        assertThat(analysis.months().get(1).tradeNumbers()).containsExactly(2, 3);
        assertThat(analysis.facts().stream().filter(f -> f.id().equals("summary:drawdown")).findFirst().orElseThrow().value())
                .isEqualByComparingTo("-.20");
    }

    private Trade trade(String timestamp, String rate) {
        long time = Instant.parse(timestamp).toEpochMilli();
        var entry = new Fill(time - 86400000, time - 86400000, time - 86400000, time - 86400000,
                new BigDecimal("100"), "ENTRY_CONDITIONS", List.of());
        var exit = new Fill(time, time, time, time, new BigDecimal("100").multiply(BigDecimal.ONE.add(new BigDecimal(rate))),
                "EXIT_CONDITIONS", List.of());
        return new Trade(entry, exit, 1, new BigDecimal(rate));
    }
}
