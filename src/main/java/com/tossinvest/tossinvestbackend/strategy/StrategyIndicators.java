package com.tossinvest.tossinvestbackend.strategy;

import java.math.BigDecimal;
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
        List<BigDecimal> result = new ArrayList<>(Collections.nCopies(bars.size(), null));
        int windowStart = 0;
        for (int i = 0; i < bars.size(); i++) {
            int from;
            if (rule.periodUnit() == PeriodUnit.BARS) {
                if (i < rule.period()) continue;
                from = i - rule.period();
            } else {
                LocalDate cutoff = date(bars.get(i)).minusWeeks(rule.period());
                if (date(bars.get(0)).isAfter(cutoff)) continue;
                while (windowStart < i && date(bars.get(windowStart)).isBefore(cutoff)) windowStart++;
                from = windowStart;
            }
            BigDecimal extreme = null;
            for (int j = from; j < i; j++) {
                BigDecimal value = rule.priceField() == PriceField.HIGH ? bars.get(j).high() : bars.get(j).low();
                if (value == null) throw new IllegalArgumentException("고가·저가 데이터가 필요합니다.");
                extreme = extreme == null ? value : rule.priceField() == PriceField.HIGH ? extreme.max(value) : extreme.min(value);
            }
            result.set(i, extreme);
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

    private static BigDecimal divide(BigDecimal value, int period) {
        return value.divide(BigDecimal.valueOf(period), 12, RoundingMode.HALF_UP);
    }

    private static LocalDate date(StrategyBar bar) { return Instant.ofEpochMilli(bar.timestamp()).atZone(MARKET_ZONE).toLocalDate(); }
}
