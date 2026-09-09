package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Version 1: explicit user rules, with no implicit baseline filters or exits. */
public record StrategyDefinition(Integer schemaVersion, String name, String originalPrompt,
                                 ConditionGroup entry, ConditionGroup exit, Risk risk) {
    public record ConditionGroup(Operator operator, List<Condition> conditions) {
        public ConditionGroup {
            // Preserve null elements until validation can report their exact paths.
            if (conditions != null) conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        }
    }

    public enum Operator { AND, OR }
    public enum AverageType { SMA, EMA }
    public enum Direction { UP, DOWN }
    public enum Comparison { GTE, LTE, CROSS_ABOVE, CROSS_BELOW }
    public enum RsiMethod { SIMPLE }
    public enum TrailingPolicy { LEGACY_STEP_3_PERCENT }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MovingAverageCross.class, name = "MA_CROSS"),
            @JsonSubTypes.Type(value = Rsi.class, name = "RSI"),
            @JsonSubTypes.Type(value = Volume.class, name = "VOLUME")
    })
    public sealed interface Condition permits MovingAverageCross, Rsi, Volume { }

    public record MovingAverageCross(AverageType averageType, Integer shortPeriod, Integer longPeriod,
                                     Direction direction) implements Condition { }
    public record Rsi(RsiMethod method, Integer period, BigDecimal threshold,
                      Comparison comparison) implements Condition { }
    public record Volume(Integer period, BigDecimal multiplier, Comparison comparison) implements Condition { }

    public record Risk(StopLoss stopLoss, TakeProfit takeProfit, TimeExit timeExit, TrailingPolicy trailing) { }
    public record StopLoss(BigDecimal rate) { }
    public record TakeProfit(BigDecimal rate) { }
    public record TimeExit(Integer days) { }
}
