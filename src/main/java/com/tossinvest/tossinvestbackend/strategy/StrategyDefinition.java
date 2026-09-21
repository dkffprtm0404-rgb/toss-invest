package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit daily rules. V2 adds OHLC, V3 relative strength, V4 an independently executed composition. */
public record StrategyDefinition(Integer schemaVersion, String name, String originalPrompt,
                                 ConditionGroup entry, ConditionGroup exit, Risk risk,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) RelativeStrength portfolio,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) CompositionDefinition composition) {
    public StrategyDefinition(Integer schemaVersion, String name, String originalPrompt, ConditionGroup entry, ConditionGroup exit, Risk risk) {
        this(schemaVersion, name, originalPrompt, entry, exit, risk, null);
    }
    public StrategyDefinition(Integer schemaVersion, String name, String originalPrompt, ConditionGroup entry, ConditionGroup exit, Risk risk, RelativeStrength portfolio) {
        this(schemaVersion, name, originalPrompt, entry, exit, risk, portfolio, null);
    }
    public enum Market { KOSPI, KOSDAQ, KOSPI_KOSDAQ }
    public enum SelectionOrder { FILTER_THEN_RANK, RANK_THEN_FILTER }
    public enum RebalanceTiming { WEEK_START, WEEK_END }
    public enum Weighting { EQUAL_SLOTS }
    public record RelativeStrength(Market market, Integer lookbackMonths, Integer topN, Integer smaPeriod,
                                   SelectionOrder selectionOrder, RebalanceTiming rebalanceTiming, Weighting weighting) { }
    public record ConditionGroup(Operator operator, List<Condition> conditions) {
        public ConditionGroup {
            // Preserve null elements until validation can report their exact paths.
            if (conditions != null) conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        }
    }

    public enum Operator { AND, OR }
    public enum AverageType { SMA, EMA }
    public enum Direction { UP, DOWN }
    public enum Comparison { GTE, LTE, CROSS_ABOVE, CROSS_BELOW, GT, LT }
    public enum RsiMethod { SIMPLE }
    public enum TrailingPolicy { LEGACY_STEP_3_PERCENT }
    public enum PeriodUnit { BARS, CALENDAR_WEEKS }
    public enum PriceField { HIGH, LOW }
    public enum PeakBasis { CLOSE, HIGH }
    public enum AtrMethod { SIMPLE, WILDER }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MovingAverageCross.class, name = "MA_CROSS"),
            @JsonSubTypes.Type(value = Rsi.class, name = "RSI"),
            @JsonSubTypes.Type(value = Volume.class, name = "VOLUME"),
            @JsonSubTypes.Type(value = RangeBreakout.class, name = "RANGE_BREAKOUT"),
            @JsonSubTypes.Type(value = MovingAverageCompare.class, name = "MA_COMPARE"),
            @JsonSubTypes.Type(value = PriceMovingAverage.class, name = "PRICE_MA"),
            @JsonSubTypes.Type(value = AtrBreakout.class, name = "ATR_BREAKOUT")
    })
    public sealed interface Condition permits MovingAverageCross, Rsi, Volume, RangeBreakout,
            MovingAverageCompare, PriceMovingAverage, AtrBreakout { }

    public record MovingAverageCross(AverageType averageType, Integer shortPeriod, Integer longPeriod,
                                     Direction direction) implements Condition { }
    public record Rsi(RsiMethod method, Integer period, BigDecimal threshold,
                      Comparison comparison) implements Condition { }
    public record Volume(Integer period, BigDecimal multiplier, Comparison comparison) implements Condition { }
    public record RangeBreakout(Integer period, PeriodUnit periodUnit, PriceField priceField,
                                Comparison comparison) implements Condition { }
    public record MovingAverageCompare(AverageType averageType, Integer shortPeriod, Integer longPeriod,
                                       Comparison comparison) implements Condition { }
    public record PriceMovingAverage(AverageType averageType, Integer period, Comparison comparison) implements Condition { }
    public record AtrBreakout(AtrMethod method, Integer period, BigDecimal multiplier, Comparison comparison) implements Condition { }

    public record Risk(StopLoss stopLoss, TakeProfit takeProfit, TimeExit timeExit, TrailingPolicy trailing,
                       @JsonInclude(JsonInclude.Include.NON_NULL) TrailingStop trailingStop,
                       @JsonInclude(JsonInclude.Include.NON_NULL) AtrStop atrStop) {
        public Risk(StopLoss stopLoss, TakeProfit takeProfit, TimeExit timeExit, TrailingPolicy trailing) {
            this(stopLoss, takeProfit, timeExit, trailing, null, null);
        }
    }
    public record TrailingStop(BigDecimal rate, PeakBasis peakBasis) { }
    public record AtrStop(AtrMethod method, Integer period, BigDecimal multiplier) { }
    public record StopLoss(BigDecimal rate) { }
    public record TakeProfit(BigDecimal rate) { }
    public record TimeExit(Integer days) { }
}
