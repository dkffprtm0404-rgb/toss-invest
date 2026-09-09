package com.tossinvest.tossinvestbackend.strategy;

import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;

/** Pure daily-close decisions shared by backtests and future paper execution adapters. */
@Component
public class StrategyEvaluator {
    public record Evidence(String path, String type, BigDecimal actualValue, BigDecimal referenceValue,
                           BigDecimal previousActualValue, BigDecimal previousReferenceValue, boolean matched) { }
    public record Decision(boolean matched, String reason, List<Evidence> evidence) {
        public Decision { evidence = List.copyOf(evidence); }
    }
    public record PositionContext(BigDecimal entryPrice, int holdingBars, BigDecimal peakPrice) { }

    public Evaluation prepare(StrategyDefinition strategy, List<StrategyBar> bars) {
        new StrategyValidator().requireValid(strategy);
        return new Evaluation(strategy, List.copyOf(bars));
    }

    public static BigDecimal returnRate(BigDecimal price, BigDecimal entryPrice) {
        return price.subtract(entryPrice).divide(entryPrice, 6, RoundingMode.HALF_UP);
    }

    public static final class Evaluation {
        private final StrategyDefinition strategy;
        private final List<StrategyBar> bars;
        private final Map<Condition, Series> indicators = new HashMap<>();
        private final Map<Condition, Integer> warmups = new HashMap<>();
        private final int requiredWarmupBars;

        private record Series(List<BigDecimal> actual, List<BigDecimal> reference, Comparison comparison) { }

        private Evaluation(StrategyDefinition strategy, List<StrategyBar> bars) {
            this.strategy = strategy;
            this.bars = bars;
            List<BigDecimal> closes = bars.stream().map(StrategyBar::close).toList();
            List<BigDecimal> volumes = bars.stream().map(StrategyBar::volume).toList();
            prepareGroup(strategy.entry(), closes, volumes);
            prepareGroup(strategy.exit(), closes, volumes);
            var periods = strategy.entry().conditions().stream().mapToInt(warmups::get);
            requiredWarmupBars = strategy.entry().operator() == Operator.OR
                    ? periods.min().orElse(0) : periods.max().orElse(0);
        }

        public int requiredWarmupBars() { return requiredWarmupBars; }

        public boolean entryReady(int index) {
            var conditions = strategy.entry().conditions().stream();
            return strategy.entry().operator() == Operator.OR
                    ? conditions.anyMatch(c -> ready(indicators.get(c), index))
                    : conditions.allMatch(c -> ready(indicators.get(c), index));
        }

        public Decision entry(int index) { return group(strategy.entry(), "entry", index, "ENTRY_CONDITIONS"); }

        public Decision exit(int index, PositionContext position) {
            BigDecimal price = bars.get(index).close();
            Risk risk = strategy.risk();
            if (risk != null) {
                BigDecimal start = new BigDecimal(position.holdingBars() > 3 ? "0.015" : "0.03");
                BigDecimal activationPrice = targetPrice(position, start);
                boolean trailingActive = risk.trailing() != null && position.peakPrice().compareTo(activationPrice) >= 0;
                if (risk.stopLoss() != null && price.compareTo(targetPrice(position, risk.stopLoss().rate())) <= 0)
                    return riskDecision("STOP_LOSS", "risk.stopLoss.rate", price, targetPrice(position, risk.stopLoss().rate()));
                if (risk.takeProfit() != null && price.compareTo(targetPrice(position, risk.takeProfit().rate())) >= 0)
                    return riskDecision("TAKE_PROFIT", "risk.takeProfit.rate", price, targetPrice(position, risk.takeProfit().rate()));
                if (risk.timeExit() != null && position.holdingBars() > risk.timeExit().days() && !trailingActive)
                    return riskDecision("TIME_EXIT", "risk.timeExit.days", BigDecimal.valueOf(position.holdingBars()),
                            BigDecimal.valueOf(risk.timeExit().days()));
                if (trailingActive) {
                    BigDecimal priceStep = position.entryPrice().multiply(new BigDecimal("0.03"));
                    BigDecimal completedSteps = position.peakPrice().subtract(activationPrice).divideToIntegralValue(priceStep);
                    BigDecimal stopPrice = position.entryPrice().add(completedSteps.multiply(priceStep));
                    if (price.compareTo(stopPrice) < 0) {
                        return new Decision(true, "TRAILING_STOP", List.of(
                                new Evidence("risk.trailing", "TRAILING_STOP", price, stopPrice, null, null, true),
                                new Evidence("risk.trailing.activation", "PEAK_PRICE", position.peakPrice(), activationPrice, null, null, true)));
                    }
                }
            }
            return group(strategy.exit(), "exit", index, "EXIT_CONDITIONS");
        }

        private BigDecimal targetPrice(PositionContext position, BigDecimal rate) {
            return position.entryPrice().multiply(BigDecimal.ONE.add(rate));
        }

        private Decision riskDecision(String reason, String path, BigDecimal actual, BigDecimal reference) {
            return new Decision(true, reason, List.of(new Evidence(path, reason, actual, reference, null, null, true)));
        }

        private void prepareGroup(ConditionGroup group, List<BigDecimal> closes, List<BigDecimal> volumes) {
            if (group == null) return;
            for (Condition condition : group.conditions()) {
                if (indicators.containsKey(condition)) continue;
                Series series;
                int warmup;
                if (condition instanceof MovingAverageCross ma) {
                    List<BigDecimal> shortMa = average(closes, ma.shortPeriod(), ma.averageType());
                    List<BigDecimal> longMa = average(closes, ma.longPeriod(), ma.averageType());
                    series = new Series(shortMa, longMa, ma.direction() == Direction.UP ? Comparison.CROSS_ABOVE : Comparison.CROSS_BELOW);
                    warmup = ma.longPeriod();
                } else if (condition instanceof Rsi rsi) {
                    List<BigDecimal> values = new ArrayList<>();
                    for (int i = 0; i < closes.size(); i++)
                        values.add(TechnicalIndicatorCalculator.rsi(closes.subList(0, i + 1), rsi.period()));
                    series = new Series(values, java.util.Collections.nCopies(closes.size(), rsi.threshold()), rsi.comparison());
                    warmup = rsi.period() + (isCross(rsi.comparison()) ? 1 : 0);
                } else if (condition instanceof Volume volume) {
                    List<BigDecimal> thresholds = new ArrayList<>();
                    for (int i = 0; i < volumes.size(); i++) {
                        BigDecimal average = TechnicalIndicatorCalculator.recentAvgVolume(volumes.subList(0, i + 1), volume.period());
                        // An all-zero reference window cannot establish a volume ratio.
                        thresholds.add(average == null || average.signum() == 0 ? null : average.multiply(volume.multiplier()));
                    }
                    series = new Series(volumes, thresholds, volume.comparison());
                    warmup = volume.period();
                } else {
                    throw new IllegalArgumentException("Unsupported condition");
                }
                indicators.put(condition, series);
                warmups.put(condition, warmup);
            }
        }

        private List<BigDecimal> average(List<BigDecimal> closes, int period, AverageType type) {
            return type == AverageType.SMA ? TechnicalIndicatorCalculator.smaSeries(closes, period)
                    : TechnicalIndicatorCalculator.emaSeries(closes, period);
        }

        private Decision group(ConditionGroup group, String path, int index, String reason) {
            if (group == null) return new Decision(false, "NO_SIGNAL", List.of());
            List<Evidence> evidence = new ArrayList<>();
            boolean matched = group.operator() != Operator.OR;
            for (int i = 0; i < group.conditions().size(); i++) {
                Condition condition = group.conditions().get(i);
                Series series = indicators.get(condition);
                BigDecimal actual = series.actual().get(index), reference = series.reference().get(index);
                BigDecimal previousActual = index > 0 ? series.actual().get(index - 1) : null;
                BigDecimal previousReference = index > 0 ? series.reference().get(index - 1) : null;
                boolean passes = compare(series.comparison(), actual, reference, previousActual, previousReference);
                String type = condition instanceof MovingAverageCross ? "MA_CROSS" : condition instanceof Rsi ? "RSI" : "VOLUME";
                evidence.add(new Evidence(path + ".conditions[" + i + "]", type, actual, reference,
                        previousActual, previousReference, passes));
                matched = group.operator() == Operator.OR ? matched || passes : matched && passes;
            }
            return new Decision(matched, matched ? reason : "NO_SIGNAL", evidence);
        }

        private boolean compare(Comparison comparison, BigDecimal actual, BigDecimal reference,
                                BigDecimal previousActual, BigDecimal previousReference) {
            if (actual == null || reference == null) return false;
            if (isCross(comparison) && (previousActual == null || previousReference == null)) return false;
            return switch (comparison) {
                case GTE -> actual.compareTo(reference) >= 0;
                case LTE -> actual.compareTo(reference) <= 0;
                case CROSS_ABOVE -> previousActual.compareTo(previousReference) <= 0 && actual.compareTo(reference) > 0;
                case CROSS_BELOW -> previousActual.compareTo(previousReference) >= 0 && actual.compareTo(reference) < 0;
            };
        }

        private boolean ready(Series series, int index) {
            return series.actual().get(index) != null && series.reference().get(index) != null
                    && (!isCross(series.comparison()) || index > 0 && series.actual().get(index - 1) != null
                    && series.reference().get(index - 1) != null);
        }

        private static boolean isCross(Comparison comparison) {
            return comparison == Comparison.CROSS_ABOVE || comparison == Comparison.CROSS_BELOW;
        }
    }
}
