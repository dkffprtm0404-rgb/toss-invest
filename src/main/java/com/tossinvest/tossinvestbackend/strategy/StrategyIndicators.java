package com.tossinvest.tossinvestbackend.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;

/** Indicators only read the current or earlier completed bars. Null means insufficient history. */
final class StrategyIndicators {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private StrategyIndicators() { }

    static List<BigDecimal> range(List<StrategyBar> bars, RangeBreakout rule) {
        return range(bars, rule.period(), rule.periodUnit(), rule.priceField());
    }

    static List<BigDecimal> range(List<StrategyBar> bars, int period, PeriodUnit periodUnit, PriceField priceField) {
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        int windowStart = 0;
        for (int i = 0; i < bars.size(); i++) {
            int from;
            if (periodUnit == PeriodUnit.BARS) {
                if (i < period) continue;
                from = i - period;
            } else {
                LocalDate cutoff = date(bars.get(i)).minusWeeks(period);
                if (date(bars.get(0)).isAfter(cutoff)) continue;
                while (windowStart < i && date(bars.get(windowStart)).isBefore(cutoff)) windowStart++;
                from = windowStart;
            }
            BigDecimal extreme = null;
            for (int j = from; j < i; j++) {
                BigDecimal value = priceField == PriceField.HIGH ? bars.get(j).high() : bars.get(j).low();
                if (value == null) throw new IllegalArgumentException("고가·저가 데이터가 필요합니다.");
                extreme = extreme == null ? value : priceField == PriceField.HIGH ? extreme.max(value) : extreme.min(value);
            }
            result.set(i, extreme);
        }
        return result;
    }

    /** Endpoint trend: compare with the last stored trading bar on or before the KST calendar cutoff. */
    static List<BigDecimal> monthsAgo(List<StrategyBar> bars, List<BigDecimal> values, int months) {
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        int reference = -1;
        for (int i = 0; i < bars.size(); i++) {
            LocalDate cutoff = date(bars.get(i)).minusMonths(months);
            while (reference + 1 < i && !date(bars.get(reference + 1)).isAfter(cutoff)) reference++;
            // A missing or unready historical average is not replaced with a newer value.
            if (reference >= 0) result.set(i, values.get(reference));
        }
        return result;
    }

    static List<BigDecimal> atr(List<StrategyBar> bars, int period, AtrMethod method) {
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        List<BigDecimal> ranges = new ArrayList<>(Collections.nCopies(bars.size(), null));
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal previous = null;
        for (int i = 1; i < bars.size(); i++) {
            StrategyBar bar = bars.get(i);
            if (bar.high() == null || bar.low() == null) throw new IllegalArgumentException("ATR 계산에 고가·저가 데이터가 필요합니다.");
            BigDecimal close = bars.get(i - 1).close();
            BigDecimal tr = bar.high().subtract(bar.low()).max(bar.high().subtract(close).abs()).max(bar.low().subtract(close).abs());
            ranges.set(i, tr);
            sum = sum.add(tr);
            if (i > period) sum = sum.subtract(ranges.get(i - period));
            if (i < period) continue;
            previous = method == AtrMethod.SIMPLE || previous == null ? divide(sum, period)
                    : divide(previous.multiply(BigDecimal.valueOf(period - 1L)).add(tr), period);
            result.set(i, previous);
        }
        return result;
    }

    static List<BigDecimal> atrThreshold(List<StrategyBar> bars, AtrBreakout rule) {
        List<BigDecimal> atr = atr(bars, rule.period(), rule.method());
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        for (int i = 1; i < bars.size(); i++) {
            if (atr.get(i - 1) != null)
                result.set(i, bars.get(i - 1).close().add(atr.get(i - 1).multiply(rule.multiplier())));
        }
        return result;
    }

    record MacdSeries(List<BigDecimal> line, List<BigDecimal> signal, List<BigDecimal> histogram) { }

    static MacdSeries macd(List<StrategyBar> bars, Macd rule) {
        List<BigDecimal> closes = bars.stream().map(StrategyBar::close).toList();
        List<BigDecimal> fast = preciseEma(closes, rule.fastPeriod());
        List<BigDecimal> slow = preciseEma(closes, rule.slowPeriod());
        List<BigDecimal> line = new ArrayList<>(Collections.nCopies(bars.size(), null));
        for (int i = rule.slowPeriod() - 1; i < bars.size(); i++) line.set(i, fast.get(i).subtract(slow.get(i)));
        int offset = Math.min(rule.slowPeriod() - 1, bars.size());
        List<BigDecimal> signalTail = preciseEma(line.subList(offset, line.size()), rule.signalPeriod());
        List<BigDecimal> signal = new ArrayList<>(Collections.nCopies(bars.size(), null));
        List<BigDecimal> histogram = new ArrayList<>(Collections.nCopies(bars.size(), null));
        for (int i = offset; i < bars.size(); i++) {
            BigDecimal value = signalTail.get(i - offset);
            signal.set(i, value);
            if (value != null) histogram.set(i, line.get(i).subtract(value));
        }
        return new MacdSeries(line, signal, histogram);
    }

    /** New indicators retain 12 fractional places; existing SMA/EMA contracts are unchanged. */
    private static List<BigDecimal> preciseEma(List<BigDecimal> values, int period) {
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(values.size(), null));
        if (values.size() < period) return result;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(values.get(i));
        BigDecimal previous = divide(sum, period);
        result.set(period - 1, previous);
        for (int i = period; i < values.size(); i++) {
            BigDecimal adjustment = values.get(i).subtract(previous).multiply(BigDecimal.valueOf(2))
                    .divide(BigDecimal.valueOf(period + 1L), 12, RoundingMode.HALF_UP);
            previous = previous.add(adjustment);
            result.set(i, previous);
        }
        return result;
    }

    /** Current-close window, population variance (divide by N), SMA middle band. */
    static List<BigDecimal> bollinger(List<StrategyBar> bars, Bollinger rule) {
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        MathContext precision = MathContext.DECIMAL128;
        for (int i = rule.period() - 1; i < bars.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            int from = i - rule.period() + 1;
            for (int j = from; j <= i; j++) sum = sum.add(bars.get(j).close());
            BigDecimal mean = sum.divide(BigDecimal.valueOf(rule.period()), precision);
            BigDecimal value = mean;
            if (rule.band() != BollingerBand.MIDDLE) {
                BigDecimal deviations = BigDecimal.ZERO;
                for (int j = from; j <= i; j++) {
                    BigDecimal difference = bars.get(j).close().subtract(mean);
                    deviations = deviations.add(difference.multiply(difference));
                }
                BigDecimal deviation = deviations.divide(BigDecimal.valueOf(rule.period()), precision).sqrt(precision);
                BigDecimal width = deviation.multiply(rule.multiplier());
                value = rule.band() == BollingerBand.UPPER ? mean.add(width) : mean.subtract(width);
            }
            result.set(i, value.setScale(12, RoundingMode.HALF_UP));
        }
        return result;
    }

    private static BigDecimal divide(BigDecimal value, int period) {
        return value.divide(BigDecimal.valueOf(period), 12, RoundingMode.HALF_UP);
    }

    private static LocalDate date(StrategyBar bar) { return Instant.ofEpochMilli(bar.timestamp()).atZone(MARKET_ZONE).toLocalDate(); }
}
