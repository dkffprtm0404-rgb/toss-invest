package com.tossinvest.tossinvestbackend.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 기존 동작을 기록한다. 정책 변경 시 명세와 함께 기대값을 검토한다.
class BacktestEngineCharacterizationTests {

    private final BacktestEngine engine = new BacktestEngine();

    @Test
    void emptyAndWarmupOnlyDataProduceNoTrades() {
        assertThat(engine.run("TEST", List.of(), BacktestParams.builder().build()).getTrades()).isEmpty();
        assertThat(engine.run("TEST", candles(22), BacktestParams.builder().build()).getTrades()).isEmpty();
    }

    @Test
    void stopLossAtThresholdClosesAtTheSameDaysClose() {
        var indicators = exitScenario("100", "98");
        indicators.opens[23] = new BigDecimal("101");

        BacktestTrade trade = engine.runWithIndicators("TEST", indicators, exitParams()).getTrades().get(0);

        assertThat(trade.entryIndex).isEqualTo(22);
        assertThat(trade.exitIndex).isEqualTo(23);
        assertThat(trade.exitPrice).isEqualByComparingTo("98");
        assertThat(trade.returnRate).isEqualByComparingTo("-0.02");
        assertThat(trade.exitReason).isEqualTo("STOP_LOSS");
    }

    @Test
    void intradayLowDoesNotTriggerStopLossWhenCloseRecovers() {
        var indicators = exitScenario("100", "99");
        indicators.lows[23] = new BigDecimal("90");

        BacktestTrade trade = engine.runWithIndicators("TEST", indicators, exitParams()).getTrades().get(0);

        assertThat(trade.exitReason).isEqualTo("END_OF_DATA");
        assertThat(trade.exitPrice).isEqualByComparingTo("99");
    }

    @Test
    void fiveDayLimitClosesOnSixthFollowingCandleAndAllowsSameBarReentry() {
        var indicators = exitScenario("100", "100", "100", "100", "100", "100", "100");

        List<BacktestTrade> trades = engine.runWithIndicators("TEST", indicators, exitParams()).getTrades();

        assertThat(trades).hasSize(2);
        assertThat(trades.get(0).holdingDays).isEqualTo(6);
        assertThat(trades.get(0).exitReason).isEqualTo("TIME_EXIT");
        assertThat(trades.get(1).entryIndex).isEqualTo(trades.get(0).exitIndex);
        assertThat(trades.get(1).exitReason).isEqualTo("END_OF_DATA");
        assertThat(trades.get(1).holdingDays).isZero();
    }

    @Test
    void sixPercentPeakRaisesStopToThreePercent() {
        var indicators = exitScenario("100", "106", "102");

        BacktestTrade trade = engine.runWithIndicators("TEST", indicators, exitParams()).getTrades().get(0);

        assertThat(trade.exitIndex).isEqualTo(24);
        assertThat(trade.exitReason).isEqualTo("TRAILING_STOP");
        assertThat(trade.returnRate).isEqualByComparingTo("0.02");
    }

    @Test
    void losingTwentyTradesPreventsReentryEvenAfterLongIdlePeriod() {
        var indicators = engine.precompute(candles(300));
        neutralizeTrendIndicators(indicators);
        BigDecimal price = new BigDecimal("1000000");
        for (int i = 22; i < indicators.n; i++) {
            indicators.closes[i] = price;
            indicators.opens[i] = price;
            price = price.multiply(new BigDecimal("0.97"));
        }

        List<BacktestTrade> trades = engine.runWithIndicators("TEST", indicators, exitParams()).getTrades();

        assertThat(trades).hasSize(20);
        assertThat(trades).allMatch(t -> t.returnRate.signum() < 0);
        assertThat(trades.get(19).exitIndex).isLessThan(250);
    }

    @Test
    void appendingFutureCandlesDoesNotChangeEarlierIndicators() {
        List<CandleEntity> history = candles(35);
        var prefix = engine.precompute(history.subList(0, 28));
        var full = engine.precompute(history);

        assertThat(Arrays.copyOf(full.ema5, 28)).containsExactly(prefix.ema5);
        assertThat(Arrays.copyOf(full.ema20, 28)).containsExactly(prefix.ema20);
        assertThat(Arrays.copyOf(full.rsi, 28)).containsExactly(prefix.rsi);
        assertThat(Arrays.copyOf(full.avgTradingValue5, 28)).containsExactly(prefix.avgTradingValue5);
        assertThat(Arrays.copyOf(full.recentHigh5, 28)).containsExactly(prefix.recentHigh5);
        assertThat(Arrays.copyOf(full.volatilityTarget, 28)).containsExactly(prefix.volatilityTarget);
    }

    @Test
    void lookbackExcludesCurrentCandle() {
        List<CandleEntity> history = candles(28);
        for (int i = 22; i < 27; i++) {
            history.get(i).setHighPrice(new BigDecimal("110"));
            history.get(i).setVolume(new BigDecimal("10"));
        }
        history.get(27).setHighPrice(new BigDecimal("1000"));
        history.get(27).setVolume(new BigDecimal("1000"));

        var indicators = engine.precompute(history);

        assertThat(indicators.recentHigh5[27]).isEqualByComparingTo("110");
        assertThat(indicators.avgVolume5[27]).isEqualByComparingTo("10");
    }

    @Test
    void goldenCrossAloneDoesNotMeetDefaultBuyScore() {
        var indicators = buyScenario();
        indicators.ema5[21] = new BigDecimal("99");
        indicators.ema5[22] = new BigDecimal("101");
        indicators.volumes[22] = new BigDecimal("100");
        indicators.volatilityTarget[22] = new BigDecimal("106");

        assertThat(engine.runWithIndicators("TEST", indicators, BacktestParams.builder().build()).getTrades())
                .isEmpty();
    }

    @Test
    void volumeBreakoutAndVolatilityTargetMeetDefaultScoreExactly() {
        var params = BacktestParams.builder().build();
        var indicators = buyScenario();

        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).hasSize(1);

        indicators.volumes[22] = new BigDecimal("149.99");
        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).isEmpty();
    }

    @Test
    void rsiEightyBlocksEntryButValueBelowEightyDoesNot() {
        var params = BacktestParams.builder().build();
        var indicators = buyScenario();
        indicators.rsi[22] = new BigDecimal("79.99");
        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).hasSize(1);

        indicators.rsi[22] = new BigDecimal("80");
        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).isEmpty();
    }

    @Test
    void tradingValueThresholdIsInclusive() {
        var params = BacktestParams.builder().build();
        var indicators = buyScenario();
        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).hasSize(1);

        indicators.avgTradingValue5[22] = new BigDecimal("2999999999");
        assertThat(engine.runWithIndicators("TEST", indicators, params).getTrades()).isEmpty();
    }

    @Test
    void gapAndDailySurgeAtTheirThresholdsBlockEntry() {
        var params = BacktestParams.builder().build();
        var gap = buyScenario();
        gap.opens[22] = new BigDecimal("108.90");
        assertThat(engine.runWithIndicators("TEST", gap, params).getTrades()).isEmpty();

        var surge = buyScenario();
        surge.closes[22] = new BigDecimal("118.80");
        assertThat(engine.runWithIndicators("TEST", surge, params).getTrades()).isEmpty();
    }

    private BacktestEngine.PrecomputedIndicators buyScenario() {
        var indicators = engine.precompute(candles(23));
        neutralizeTrendIndicators(indicators);
        indicators.closes[22] = new BigDecimal("105");
        indicators.recentHigh5[22] = new BigDecimal("104");
        indicators.avgVolume5[22] = new BigDecimal("100");
        indicators.volumes[22] = new BigDecimal("150");
        indicators.volatilityTarget[22] = new BigDecimal("105");
        indicators.avgTradingValue5[22] = new BigDecimal("3000000000");
        return indicators;
    }

    private BacktestEngine.PrecomputedIndicators exitScenario(String... closes) {
        var indicators = engine.precompute(candles(22 + closes.length));
        neutralizeTrendIndicators(indicators);
        for (int i = 0; i < closes.length; i++) {
            indicators.closes[22 + i] = new BigDecimal(closes[i]);
        }
        return indicators;
    }

    private void neutralizeTrendIndicators(BacktestEngine.PrecomputedIndicators indicators) {
        // 각 시나리오에서 지정하지 않은 RSI·교차 신호가 개입하지 않게 한다.
        Arrays.fill(indicators.rsi, new BigDecimal("50"));
        Arrays.fill(indicators.ema5, new BigDecimal("100"));
        Arrays.fill(indicators.ema20, new BigDecimal("100"));
    }

    private BacktestParams exitParams() {
        return BacktestParams.builder()
                .buyScoreThreshold(0)
                .minAvgTradingValueKrw(BigDecimal.ZERO)
                .gapUpInvalidateRate(new BigDecimal("100"))
                .daySurgeInvalidateRate(new BigDecimal("100"))
                .build();
    }

    private List<CandleEntity> candles(int count) {
        List<CandleEntity> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(new CandleEntity("TEST", i * 86_400_000L,
                    new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("98"),
                    BigDecimal.valueOf(i % 2 == 0 ? 100 : 99), new BigDecimal("100000000")));
        }
        return candles;
    }
}
