package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static org.assertj.core.api.Assertions.*;

class UserStrategyBacktestEngineTests {
    private final UserStrategyBacktestEngine engine = new UserStrategyBacktestEngine(new StrategyEvaluator());

    @Test
    void closeAndNextOpenProduceDifferentFillsWithSignalEvidence() throws Exception {
        var candles = candles(100, 100, 94, 93, 100);
        candles.get(2).setOpenPrice(bd(100));
        candles.get(3).setOpenPrice(bd(92));
        candles.get(3).setLowPrice(bd(92));
        var close = engine.run(request(SAME_DAY_CLOSE, "{\"stopLoss\":{\"rate\":-0.05}}"), candles);
        var next = engine.run(request(NEXT_DAY_OPEN, "{\"stopLoss\":{\"rate\":-0.05}}"), candles);
        var c = close.trades().get(0);
        var n = next.trades().get(0);
        assertThat(c.entry().price()).isEqualByComparingTo("100");
        assertThat(c.exit().price()).isEqualByComparingTo("94");
        assertThat(c.returnRate()).isEqualByComparingTo("-0.06");
        assertThat(n.exit().price()).isEqualByComparingTo("92");
        assertThat(n.returnRate()).isEqualByComparingTo("-0.08");
        assertThat(n.entry().signalTimestamp()).isEqualTo(time(1, 15, 30));
        assertThat(n.entry().executionTimestamp()).isEqualTo(time(2, 9, 0));
        assertThat(n.exit().signalTimestamp()).isEqualTo(time(2, 15, 30));
        assertThat(n.exit().executionTimestamp()).isEqualTo(time(3, 9, 0));
        assertThat(n.entry().evidence()).isNotEmpty();
        assertThat(n.exit().reason()).isEqualTo("STOP_LOSS");
    }

    @Test
    void strategyThresholdChangesActualTradesWithoutAddingLegacyFilters() throws Exception {
        var candles = candles(100, 100, 94, 93);
        var tight = engine.run(request(SAME_DAY_CLOSE, "{\"stopLoss\":{\"rate\":-0.05}}"), candles);
        var loose = engine.run(request(SAME_DAY_CLOSE, "{\"stopLoss\":{\"rate\":-0.10}}"), candles);
        assertThat(tight.trades()).hasSize(1);
        assertThat(loose.trades()).isEmpty();
        assertThat(loose.openPosition().unrealizedReturnRate()).isEqualByComparingTo("-0.07");
    }

    @Test
    void endOfDataLeavesOpenPositionAndUnfilledOrdersSeparateFromClosedMetrics() throws Exception {
        var request = request(NEXT_DAY_OPEN, "{\"stopLoss\":{\"rate\":-0.05}}");
        var onlySignal = engine.run(request, candles(100, 100));
        assertThat(onlySignal.trades()).isEmpty();
        assertThat(onlySignal.openPosition()).isNull();
        assertThat(onlySignal.pendingOrder().action()).isEqualTo("BUY");
        assertThat(onlySignal.pendingOrder().status()).isEqualTo("NO_NEXT_BAR");
        var loss = candles(100, 100, 94);
        loss.get(2).setOpenPrice(bd(100));
        var pendingExit = engine.run(request, loss);
        assertThat(pendingExit.pendingOrder().action()).isEqualTo("SELL");
        assertThat(pendingExit.openPosition().unrealizedReturnRate()).isEqualByComparingTo("-0.06");
        assertThat(pendingExit.metrics().closedTrades()).isZero();
        assertThat(pendingExit.metrics().sumTradeReturnRate()).isZero();
    }

    @Test
    void intradayLowCannotTriggerStopAndSameCloseExitCannotReenter() throws Exception {
        var candles = candles(100, 100, 99, 94, 100);
        candles.get(2).setLowPrice(bd(80));
        var result = engine.run(request(SAME_DAY_CLOSE, "{\"stopLoss\":{\"rate\":-0.05}}"), candles);
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).exit().executionTimestamp()).isEqualTo(time(3, 15, 30));
        assertThat(result.openPosition().entry().executionTimestamp()).isEqualTo(time(4, 15, 30));
    }

    @Test
    void nextOpenExitCanCreateANewOrderAtThatDaysClose() throws Exception {
        var candles = candles(100, 100, 94, 92);
        candles.get(2).setOpenPrice(bd(100));
        var result = engine.run(request(NEXT_DAY_OPEN, "{\"stopLoss\":{\"rate\":-0.05}}"), candles);
        assertThat(result.trades()).hasSize(1);
        assertThat(result.openPosition()).isNull();
        assertThat(result.pendingOrder().action()).isEqualTo("BUY");
        assertThat(result.pendingOrder().signalTimestamp()).isEqualTo(time(3, 15, 30));
    }

    @Test
    void warmupUsesHistoryButCannotTradeBeforeStartOrAfterEnd() throws Exception {
        var base = request(NEXT_DAY_OPEN, null);
        var request = new UserStrategyBacktestRequest(base.strategy(), "005930", date(2), date(2), NEXT_DAY_OPEN);
        var result = engine.run(request, candles(100, 100, 100, 120));
        assertThat(result.warmupBars()).isEqualTo(2);
        assertThat(result.candleCount()).isEqualTo(1);
        assertThat(result.openPosition()).isNull();
        assertThat(result.pendingOrder().signalTimestamp()).isEqualTo(time(2, 15, 30));
    }

    @Test
    void noDataInsufficientHistoryAndNoSignalAreDistinguished() throws Exception {
        var request = request(SAME_DAY_CLOSE, null);
        assertThat(engine.run(request, List.of()).status()).isEqualTo("NO_DATA");
        assertThat(engine.run(request, candles(100)).status()).isEqualTo("INSUFFICIENT_DATA");
        var candles = candles(100, 100);
        candles.get(1).setVolume(bd(1));
        assertThat(engine.run(request, candles).status()).isEqualTo("NO_TRADES");
    }

    @Test
    void invalidPricesAndDuplicateDatesAreRejectedInsteadOfFabricatingTrades() throws Exception {
        var request = request(NEXT_DAY_OPEN, null);
        var invalid = candles(100, 100, 100);
        invalid.get(2).setOpenPrice(BigDecimal.ZERO);
        assertThatThrownBy(() -> engine.run(request, invalid)).isInstanceOf(UserStrategyBacktestEngine.DataException.class);
        var duplicate = candles(100, 100);
        duplicate.get(1).setTimestamp(duplicate.get(0).getTimestamp() + 1000);
        assertThatThrownBy(() -> engine.run(request, duplicate)).isInstanceOf(UserStrategyBacktestEngine.DataException.class);
    }

    @Test
    void addingFutureBarsCannotChangeCompletedTradesOrOldSignals() throws Exception {
        var request = request(NEXT_DAY_OPEN, "{\"stopLoss\":{\"rate\":-0.05}}");
        var original = candles(100, 100, 94, 92);
        original.get(2).setOpenPrice(bd(100));
        var extended = new ArrayList<>(original);
        extended.add(candles(100, 100, 100, 100, 999).get(4));
        var first = engine.run(request, original);
        var second = engine.run(request, extended);
        assertThat(second.trades().get(0)).isEqualTo(first.trades().get(0));
        assertThat(second.openPosition().entry().signalTimestamp()).isEqualTo(first.pendingOrder().signalTimestamp());
    }

    @Test
    void missingExecutionSettingsAreNotDefaulted() throws Exception {
        var base = request(SAME_DAY_CLOSE, null);
        var missing = new UserStrategyBacktestRequest(base.strategy(), null, null, null, null);
        assertThatThrownBy(() -> engine.run(missing, candles(100, 100)))
                .isInstanceOfSatisfying(StrategyValidationException.class, ex ->
                        assertThat(ex.getIssues()).extracting("path").contains("symbol", "startDate", "endDate", "executionMode"));
    }

    @Test
    void optionalLongPeriodExitDoesNotBlockAnAvailableEntrySignal() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":1,"entry":{"conditions":[
                 {"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},
                 "exit":{"conditions":[{"type":"RSI","method":"SIMPLE","period":500,"threshold":30,"comparison":"LTE"}]}}
                """, StrategyDefinition.class);
        var result = engine.run(new UserStrategyBacktestRequest(strategy, "005930", date(0), date(30), SAME_DAY_CLOSE), candles(100, 100, 100));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.openPosition().entry().executionTimestamp()).isEqualTo(time(1, 15, 30));
    }

    @Test
    void orCanEnterOnReadyBranchWhileAndWaitsForBothBranches() throws Exception {
        String json = """
                {"schemaVersion":1,"entry":{"operator":"OR","conditions":[
                 {"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"},
                 {"type":"RSI","method":"SIMPLE","period":500,"threshold":30,"comparison":"LTE"}]}}
                """;
        var mapper = new ObjectMapper();
        var or = engine.run(new UserStrategyBacktestRequest(mapper.readValue(json, StrategyDefinition.class), "005930", date(0), date(30), SAME_DAY_CLOSE), candles(100, 100));
        var and = engine.run(new UserStrategyBacktestRequest(mapper.readValue(json.replace("OR", "AND"), StrategyDefinition.class), "005930", date(0), date(30), SAME_DAY_CLOSE), candles(100, 100));
        assertThat(or.status()).isEqualTo("COMPLETED");
        assertThat(or.requiredWarmupBars()).isEqualTo(1);
        assertThat(and.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(and.openPosition()).isNull();
    }

    @Test
    void goldenCrossWithFivePercentStopRunsThroughTheRealEngine() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":1,"entry":{"conditions":[
                 {"type":"MA_CROSS","averageType":"SMA","shortPeriod":2,"longPeriod":3,"direction":"UP"}]},
                 "risk":{"stopLoss":{"rate":-0.05}}}
                """, StrategyDefinition.class);
        var result = engine.run(new UserStrategyBacktestRequest(strategy, "005930", date(0), date(30), SAME_DAY_CLOSE),
                candles(10, 9, 8, 12, 13, 10));
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).entry().price()).isEqualByComparingTo("12");
        assertThat(result.trades().get(0).entry().executionTimestamp()).isEqualTo(time(3, 15, 30));
        assertThat(result.trades().get(0).exit().reason()).isEqualTo("STOP_LOSS");
        assertThat(result.trades().get(0).exit().price()).isEqualByComparingTo("10");
    }

    @Test
    void indicatorExitUsesSelectedConditionAndRecordsItsValues() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":1,"entry":{"conditions":[
                 {"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},
                 "exit":{"conditions":[{"type":"RSI","method":"SIMPLE","period":2,"threshold":30,"comparison":"LTE"}]}}
                """, StrategyDefinition.class);
        var result = engine.run(new UserStrategyBacktestRequest(strategy, "005930", date(0), date(30), SAME_DAY_CLOSE),
                candles(100, 101, 100, 99));
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).exit().executionTimestamp()).isEqualTo(time(3, 15, 30));
        assertThat(result.trades().get(0).exit().reason()).isEqualTo("EXIT_CONDITIONS");
        assertThat(result.trades().get(0).exit().evidence().get(0).actualValue()).isZero();
    }

    @Test
    void holdingBarsAndPeakPricesReachSharedRiskEvaluator() throws Exception {
        var timeExit = engine.run(request(SAME_DAY_CLOSE, "{\"timeExit\":{\"days\":5}}"),
                candles(100, 100, 100, 100, 100, 100, 100, 100));
        assertThat(timeExit.trades().get(0).holdingBars()).isEqualTo(6);
        assertThat(timeExit.trades().get(0).exit().reason()).isEqualTo("TIME_EXIT");
        var trailing = engine.run(request(SAME_DAY_CLOSE, "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}"), candles(100, 100, 106, 102));
        assertThat(trailing.trades().get(0).exit().reason()).isEqualTo("TRAILING_STOP");
        assertThat(trailing.trades().get(0).exit().evidence().get(0).referenceValue()).isEqualByComparingTo("103");
    }

    @Test
    void zeroVolumeFillFailsInsteadOfInventingLiquidity() throws Exception {
        var request = request(NEXT_DAY_OPEN, null);
        var candles = candles(100, 100, 100);
        candles.get(2).setVolume(BigDecimal.ZERO);
        assertThatThrownBy(() -> engine.run(request, candles)).isInstanceOf(UserStrategyBacktestEngine.DataException.class)
                .hasMessageContaining("zero-volume");
    }

    static UserStrategyBacktestRequest request(UserStrategyBacktestRequest.ExecutionMode mode, String risk) throws Exception {
        var strategy = new ObjectMapper().readValue("{\"schemaVersion\":1,\"entry\":{\"conditions\":["
                + "{\"type\":\"VOLUME\",\"period\":1,\"multiplier\":1,\"comparison\":\"GTE\"}]},\"risk\":" + risk + "}", StrategyDefinition.class);
        return new UserStrategyBacktestRequest(strategy, "005930", date(0), date(30), mode);
    }

    static List<CandleEntity> candles(int... closes) {
        List<CandleEntity> result = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) result.add(new CandleEntity("005930", time(i, 0, 0),
                bd(closes[i]), bd(1000), bd(1), bd(closes[i]), bd(100)));
        return result;
    }

    static LocalDate date(int offset) { return LocalDate.of(2025, 1, 1).plusDays(offset); }
    static long time(int offset, int hour, int minute) {
        return date(offset).atTime(hour, minute).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
    }
    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }
}
