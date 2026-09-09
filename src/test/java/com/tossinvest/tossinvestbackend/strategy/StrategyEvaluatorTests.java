package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StrategyEvaluatorTests {
    @Test
    void smaCrossIsAnEventAndUsesNoScoreOrLiquidityFilter() throws Exception {
        var evaluation = prepare("""
                {"type":"MA_CROSS","averageType":"SMA","shortPeriod":2,"longPeriod":3,"direction":"UP"}
                """, null, 10, 9, 8, 12, 13);
        assertThat(evaluation.entry(2).matched()).isFalse();
        var decision = evaluation.entry(3);
        assertThat(decision.matched()).isTrue();
        assertThat(decision.evidence().get(0).actualValue()).isEqualByComparingTo("10");
        assertThat(decision.evidence().get(0).referenceValue()).isEqualByComparingTo("9.6667");
        assertThat(evaluation.entry(4).matched()).isFalse();
    }

    @Test
    void emaDownCrossAndRsiThresholdCrossHaveDistinctDirections() throws Exception {
        var ema = prepare("""
                {"type":"MA_CROSS","averageType":"EMA","shortPeriod":2,"longPeriod":3,"direction":"DOWN"}
                """, null, 8, 9, 10, 6);
        assertThat(ema.entry(3).matched()).isTrue();
        var rsi = prepare("""
                {"type":"RSI","method":"SIMPLE","period":2,"threshold":30,"comparison":"CROSS_ABOVE"}
                """, null, 10, 9, 8, 12, 13);
        assertThat(rsi.entry(2).matched()).isFalse();
        assertThat(rsi.entry(3).matched()).isTrue();
        assertThat(rsi.entry(3).evidence().get(0).actualValue()).isEqualByComparingTo("80");
        assertThat(rsi.entry(4).matched()).isFalse();
    }

    @Test
    void volumeExcludesCurrentBarAndAndOrHaveDifferentOutcomes() throws Exception {
        String conditions = """
                [{"type":"VOLUME","period":2,"multiplier":2,"comparison":"GTE"},
                 {"type":"RSI","method":"SIMPLE","period":2,"threshold":30,"comparison":"LTE"}]
                """;
        var bars = bars(10, 11, 12);
        bars.set(2, new StrategyBar(2L, bd(12), bd(12), bd(200)));
        var and = new StrategyEvaluator().prepare(read("{\"schemaVersion\":1,\"entry\":{\"operator\":\"AND\",\"conditions\":" + conditions + "}}"), bars);
        var or = new StrategyEvaluator().prepare(read("{\"schemaVersion\":1,\"entry\":{\"operator\":\"OR\",\"conditions\":" + conditions + "}}"), bars);
        assertThat(and.entry(2).matched()).isFalse();
        assertThat(or.entry(2).matched()).isTrue();
        assertThat(or.entry(2).evidence()).hasSize(2);
        assertThat(or.entry(2).evidence().get(0).referenceValue()).isEqualByComparingTo("200");
    }

    @Test
    void timeExitUsesStrictlyMoreBarsAndDoesNotOverrideActiveTrailing() throws Exception {
        var evaluation = prepare(volume(), "{\"timeExit\":{\"days\":5},\"trailing\":\"LEGACY_STEP_3_PERCENT\"}", 100);
        assertThat(evaluation.exit(0, position(5, "0")).matched()).isFalse();
        assertThat(evaluation.exit(0, position(6, "0")).reason()).isEqualTo("TIME_EXIT");
        assertThat(evaluation.exit(0, position(6, "0.02")).matched()).isFalse();
    }

    @Test
    void trailingUsesThreePointStepsAndRelaxationAfterThreeBars() throws Exception {
        var evaluation = prepare(volume(), "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}", 102);
        assertThat(evaluation.exit(0, position(2, "0.06")).reason()).isEqualTo("TRAILING_STOP");
        assertThat(evaluation.exit(0, position(3, "0.045")).matched()).isFalse();
        assertThat(evaluation.exit(0, position(4, "0.045")).reason()).isEqualTo("TRAILING_STOP");
    }

    @Test
    void riskThresholdsAreInclusiveAndStopLossTakesPriority() throws Exception {
        var loss = prepare(volume(), "{\"stopLoss\":{\"rate\":-0.05},\"timeExit\":{\"days\":0}}", 95);
        assertThat(loss.exit(0, position(1, "0")).reason()).isEqualTo("STOP_LOSS");
        var gain = prepare(volume(), "{\"takeProfit\":{\"rate\":0.05}}", 105);
        assertThat(gain.exit(0, position(1, "0.05")).reason()).isEqualTo("TAKE_PROFIT");
    }

    @Test
    void noSelectedExitDoesNotSilentlyAddLegacyExit() throws Exception {
        var evaluation = prepare(volume(), null, 10);
        assertThat(evaluation.exit(0, position(100, "0.8")).matched()).isFalse();
    }

    @Test
    void riskThresholdsCompareExactPricesBeforeRoundingReportedReturns() throws Exception {
        var evaluation = prepare(volume(), "{\"stopLoss\":{\"rate\":-0.05}}", 2850001);
        assertThat(evaluation.exit(0, new StrategyEvaluator.PositionContext(bd(3000001), 1, bd(3000001))).matched()).isFalse();
        var precise = prepare(volume(), "{\"stopLoss\":{\"rate\":-0.3333332}}", 2);
        assertThat(precise.exit(0, new StrategyEvaluator.PositionContext(bd(3), 1, bd(3))).reason()).isEqualTo("STOP_LOSS");
        var profit = prepare(volume(), "{\"takeProfit\":{\"rate\":0.05}}", 3150001);
        assertThat(profit.exit(0, new StrategyEvaluator.PositionContext(bd(3000001), 1, bd(3150001))).matched()).isFalse();
    }

    @Test
    void trailingActivationAndProtectionDoNotRoundPricesAcrossABoundary() throws Exception {
        var evaluation = prepare(volume(), "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}", 2999999);
        assertThat(evaluation.exit(0, new StrategyEvaluator.PositionContext(bd(3000001), 2, bd(3090001))).matched()).isFalse();
        var atProtection = prepare(volume(), "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}", 103);
        assertThat(atProtection.exit(0, position(2, "0.06")).matched()).isFalse();
    }

    private StrategyEvaluator.Evaluation prepare(String condition, String risk, int... closes) throws Exception {
        return new StrategyEvaluator().prepare(read("{\"schemaVersion\":1,\"entry\":{\"conditions\":[" + condition
                + "]},\"risk\":" + risk + "}"), bars(closes));
    }

    private static String volume() {
        return "{\"type\":\"VOLUME\",\"period\":1,\"multiplier\":1,\"comparison\":\"GTE\"}";
    }

    private static StrategyEvaluator.PositionContext position(int bars, String peak) {
        return new StrategyEvaluator.PositionContext(bd(100), bars, bd(100).multiply(BigDecimal.ONE.add(new BigDecimal(peak))));
    }

    private static StrategyDefinition read(String json) throws Exception {
        return new ObjectMapper().readValue(json, StrategyDefinition.class);
    }

    private static List<StrategyBar> bars(int... closes) {
        List<StrategyBar> result = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) result.add(new StrategyBar((long) i, bd(closes[i]), bd(closes[i]), bd(100)));
        return result;
    }

    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }
}
