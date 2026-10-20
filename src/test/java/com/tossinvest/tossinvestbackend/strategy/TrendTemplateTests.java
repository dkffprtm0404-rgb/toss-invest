package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.backtest.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;

class TrendTemplateTests {
    private final StrategyJson json = new StrategyJson(new ObjectMapper());
    private final StrategyEvaluator evaluator = new StrategyEvaluator();
    private static final String TREND = """
            {"type":"MA_TREND","averageType":"SMA","period":2,"lookbackMonths":1,"direction":"UP"}
            """;
    private static final String RATIO = """
            {"type":"RANGE_RATIO","period":52,"periodUnit":"CALENDAR_WEEKS","priceField":"HIGH","multiplier":0.75,"comparison":"GTE"}
            """;

    @Test void monthComparisonUsesLastTradingBarOnOrBeforeCalendarDateNotTwentyBars() throws Exception {
        var bars = List.of(bar("2025-01-30", 10), bar("2025-01-31", 20),
                bar("2025-02-03", 100), bar("2025-03-02", 5));
        // March 2 minus one month is February 2 (Sunday): use January 31 SMA2 = 15.
        var evaluation = evaluator.prepare(definition(TREND), bars);
        assertThat(evaluation.entryReady(2)).isFalse();
        assertThat(evaluation.entry(3).matched()).isTrue();
        var evidence = evaluation.entry(3).evidence().get(0);
        assertThat(evidence.type()).isEqualTo("MA_TREND");
        // The latest average fell from 60 to 52.5, but remains above the month-ago endpoint.
        assertThat(evidence.actualValue()).isEqualByComparingTo("52.5");
        assertThat(evidence.referenceValue()).isEqualByComparingTo("15");
        assertThat(evaluation.requiredWarmupBars()).isEqualTo(3);
    }

    @Test void monthEndClampsToLeapDayAndEqualityIsNotAnUptrend() throws Exception {
        var bars = List.of(bar("2024-02-28", 10), bar("2024-02-29", 20),
                bar("2024-03-01", 30), bar("2024-03-31", 40));
        var decision = evaluator.prepare(definition(TREND), bars).entry(3);
        assertThat(decision.matched()).isTrue();
        assertThat(decision.evidence().get(0).referenceValue()).isEqualByComparingTo("15");
        var flat = List.of(bar("2024-02-28", 10), bar("2024-02-29", 20), bar("2024-03-31", 10));
        assertThat(evaluator.prepare(definition(TREND), flat).entry(2).matched()).isFalse();
        var falling = List.of(bar("2024-02-28", 10), bar("2024-02-29", 20), bar("2024-03-31", 5));
        assertThat(evaluator.prepare(definition(TREND), falling).entry(2).matched()).isFalse();
        assertThat(evaluator.prepare(definition(TREND.replace("UP", "DOWN")), falling).entry(2).matched()).isTrue();
    }

    @Test void bothCurrentAndHistoricalAveragesMustBeReady() throws Exception {
        var bars = List.of(bar("2025-01-31", 10), bar("2025-02-28", 20));
        var evaluation = evaluator.prepare(definition(TREND), bars);
        assertThat(evaluation.entryReady(1)).isFalse();
        assertThat(evaluation.entry(1).matched()).isFalse();
    }

    @Test void rangeRatioIncludesBoundaryExcludesTodayAndUsesCalendarWeeks() throws Exception {
        var bars = List.of(bar("2024-12-31", 1000), bar("2025-01-02", 200),
                bar("2026-01-01", 150, 999, 1));
        var evaluation = evaluator.prepare(definition(RATIO), bars);
        assertThat(evaluation.entry(2).matched()).isTrue();
        assertThat(evaluation.entry(2).evidence().get(0).referenceValue()).isEqualByComparingTo("150");
        assertThat(evaluation.entry(2).evidence().get(0).type()).isEqualTo("RANGE_RATIO");
        var below = List.of(bars.get(0), bars.get(1), bar("2026-01-01", 149, 999, 1));
        assertThat(evaluator.prepare(definition(RATIO), below).entry(2).matched()).isFalse();
        assertThat(evaluator.prepare(definition(RATIO), bars.subList(1, 3)).entryReady(1)).isTrue();
        var tooShort = List.of(bar("2025-01-03", 200), bars.get(2));
        assertThat(evaluator.prepare(definition(RATIO), tooShort).entryReady(1)).isFalse();
    }

    @Test void lowRatioIsThirtyPercentAboveLowAndCrossNeedsPreviousReference() throws Exception {
        String low = RATIO.replace("HIGH", "LOW").replace("0.75", "1.30");
        var bars = List.of(bar("2025-01-01", 100), bar("2025-01-03", 100), bar("2026-01-02", 130, 999, 1));
        var evidence = evaluator.prepare(definition(low), bars).entry(2).evidence().get(0);
        assertThat(evidence.matched()).isTrue();
        assertThat(evidence.referenceValue()).isEqualByComparingTo("130");
        var below = List.of(bars.get(0), bars.get(1), bar("2026-01-02", 129, 999, 1));
        assertThat(evaluator.prepare(definition(low), below).entry(2).matched()).isFalse();
        String cross = RATIO.replace("52", "1").replace("CALENDAR_WEEKS", "BARS").replace("GTE", "CROSS_ABOVE");
        var crossing = evaluator.prepare(definition(cross), List.of(bar("2025-01-01", 200), bar("2025-01-02", 150), bar("2025-01-03", 160)));
        assertThat(crossing.entryReady(1)).isFalse();
        assertThat(crossing.entry(2).matched()).isTrue();
    }

    @Test void validatesMissingParametersInvalidBoundsAndVersionWithoutFillingDefaults() throws Exception {
        var validator = new StrategyValidator();
        var missing = definition("{\"type\":\"MA_TREND\"},{\"type\":\"RANGE_RATIO\"}");
        assertThat(validator.validate(missing)).extracting(StrategyValidator.Issue::path).contains(
                "entry.conditions[0].averageType", "entry.conditions[0].period", "entry.conditions[0].lookbackMonths", "entry.conditions[0].direction",
                "entry.conditions[1].period", "entry.conditions[1].periodUnit", "entry.conditions[1].priceField", "entry.conditions[1].multiplier", "entry.conditions[1].comparison");
        for (String invalid : List.of(TREND.replace("\"lookbackMonths\":1", "\"lookbackMonths\":0"),
                TREND.replace("\"lookbackMonths\":1", "\"lookbackMonths\":61"), RATIO.replace("0.75", "0"), RATIO.replace("0.75", "-1")))
            assertThat(validator.validate(definition(invalid))).isNotEmpty();
        for (String rule : List.of(TREND, RATIO)) {
            var valid = definition(rule);
            assertThat(validator.validate(valid)).isEmpty();
            assertThat(validator.validate(new StrategyDefinition(1, valid.name(), null, valid.entry(), null, null)))
                    .extracting(StrategyValidator.Issue::path).contains("schemaVersion");
        }
        var noOperator = definition(TREND + "," + RATIO);
        assertThat(validator.validate(new StrategyDefinition(2, "누락", null,
                new StrategyDefinition.ConditionGroup(null, noOperator.entry().conditions()), null, null)))
                .extracting(StrategyValidator.Issue::path).contains("entry.operator");
    }

    @Test void allSixConditionsGateEntryAndFutureBarsDoNotRewriteSignals() throws Exception {
        StrategyDefinition strategy;
        try (var input = getClass().getResourceAsStream("/demo/strategy-trend-template.json")) {
            strategy = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), StrategyDefinition.class);
        }
        var bars = new ArrayList<StrategyBar>();
        for (int i = 0; i <= 366; i++) bars.add(bar(LocalDate.of(2025, 1, 1).plusDays(i).toString(), 100 + i));
        var evaluation = evaluator.prepare(strategy, bars);
        assertThat(evaluation.entryReady(363)).isFalse();
        assertThat(evaluation.entry(364).matched()).isTrue();
        assertThat(evaluation.entry(364).evidence()).hasSize(6).allMatch(StrategyEvaluator.Evidence::matched);
        var extended = new ArrayList<>(bars);
        extended.add(bar("2026-01-03", 999999));
        assertThat(evaluator.prepare(strategy, extended).entry(364)).isEqualTo(evaluation.entry(364));
        // Change only today's close: the ready AND group must fail if even the price filter fails.
        var failed = new ArrayList<>(bars);
        failed.set(364, bar("2025-12-31", 50));
        assertThat(evaluator.prepare(strategy, failed).entryReady(364)).isTrue();
        assertThat(evaluator.prepare(strategy, failed).entry(364).matched()).isFalse();
        var candles = bars.stream().map(b -> new CandleEntity("005930", b.timestamp(), b.open(), b.high(), b.low(), b.close(), b.volume())).toList();
        var engine = new UserStrategyBacktestEngine(evaluator);
        for (var mode : List.of(SAME_DAY_CLOSE, NEXT_DAY_OPEN)) {
            var result = engine.run(new UserStrategyBacktestRequest(strategy, "005930", LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2), mode), candles);
            assertThat(result.openPosition()).isNotNull();
            assertThat(result.openPosition().entry().signalBarTimestamp()).isEqualTo(bars.get(364).timestamp());
            assertThat(result.openPosition().entry().executionBarTimestamp()).isEqualTo(bars.get(mode == SAME_DAY_CLOSE ? 364 : 365).timestamp());
        }
    }

    private StrategyDefinition definition(String rules) throws Exception {
        return json.read("{\"schemaVersion\":2,\"name\":\"추세 테스트\",\"entry\":{\"operator\":\"AND\",\"conditions\":[" + rules + "]}}", StrategyDefinition.class);
    }
    private static StrategyBar bar(String date, int close) { return bar(date, close, close, close); }
    private static StrategyBar bar(String date, int close, int high, int low) {
        return new StrategyBar(LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),
                BigDecimal.valueOf(close), BigDecimal.valueOf(close), BigDecimal.TEN, BigDecimal.valueOf(high), BigDecimal.valueOf(low));
    }
}
