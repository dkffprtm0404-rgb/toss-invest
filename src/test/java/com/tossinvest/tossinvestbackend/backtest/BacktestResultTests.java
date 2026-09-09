package com.tossinvest.tossinvestbackend.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestResultTests {

    @Test
    void emptyTradesReturnZeroMetrics() {
        BacktestResult result = result(List.of());

        assertThat(result.getTotalTrades()).isZero();
        assertThat(result.getWinRate()).isEqualByComparingTo("0");
        assertThat(result.getCumulativeReturn()).isEqualByComparingTo("0");
        assertThat(result.getMaxDrawdown()).isEqualByComparingTo("0");
        assertThat(result.getSharpeRatio()).isEqualByComparingTo("0");
    }

    @Test
    void metricsUseTradeReturnsRatherThanAccountEquity() {
        BacktestResult result = result(List.of(trade("0.10"), trade("-0.10")));

        assertThat(result.getWinRate()).isEqualByComparingTo("0.5");
        assertThat(result.getCumulativeReturn()).isEqualByComparingTo("0");
        assertThat(result.getMaxDrawdown()).isEqualByComparingTo("-0.10");
        assertThat(result.getProfitFactor()).isEqualByComparingTo("1");
        assertThat(result.getSharpeRatio()).isEqualByComparingTo("0");
    }

    @Test
    void winningOnlyTradesUseProfitFactorThreeAndZeroSharpeForConstantReturns() {
        BacktestResult result = result(List.of(trade("0.10"), trade("0.10")));

        assertThat(result.getProfitFactor()).isEqualByComparingTo("3");
        assertThat(result.getSharpeRatio()).isEqualByComparingTo("0");
        assertThat(result.getCumulativeReturn()).isEqualByComparingTo("0.20");
    }

    private BacktestResult result(List<BacktestTrade> trades) {
        return BacktestResult.from("TEST", BacktestParams.builder().build(), trades);
    }

    private BacktestTrade trade(String returnRate) {
        BigDecimal rate = new BigDecimal(returnRate);
        return new BacktestTrade(22, 23, new BigDecimal("100"),
                new BigDecimal("100").multiply(BigDecimal.ONE.add(rate)), rate, 1, "TEST");
    }
}
