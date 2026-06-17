package com.tossinvest.tossinvestbackend.signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 캔들 종가 리스트로부터 보조지표(SMA, RSI)를 계산한다.
 * 입력 리스트는 "오래된 것 -> 최신" 순서를 가정한다.
 */
public class TechnicalIndicatorCalculator {

    private TechnicalIndicatorCalculator() {
    }

    /**
     * 최근 period개 종가의 단순이동평균. 데이터가 부족하면 null.
     */
    public static BigDecimal sma(List<BigDecimal> closes, int period) {
        if (closes.size() < period) {
            return null;
        }
        List<BigDecimal> window = closes.subList(closes.size() - period, closes.size());
        BigDecimal sum = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
    }

    /**
     * 지수이동평균(EMA). 초기값은 첫 period개의 SMA로 시작하는 표준 방식.
     * 데이터가 부족하면 null.
     */
    public static BigDecimal ema(List<BigDecimal> closes, int period) {
        List<BigDecimal> series = emaSeries(closes, period);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /**
     * 전체 구간에 대한 EMA 시계열 (앞쪽 period-1개는 null로 패딩).
     * 골든/데드크로스 판정을 위해 직전 값과 비교가 필요할 때 사용한다.
     */
    public static List<BigDecimal> emaSeries(List<BigDecimal> closes, int period) {
        List<BigDecimal> result = new java.util.ArrayList<>();
        if (closes.size() < period) {
            for (int i = 0; i < closes.size(); i++) result.add(null);
            return result;
        }

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal prevEma = null;

        for (int i = 0; i < closes.size(); i++) {
            if (i + 1 < period) {
                result.add(null);
                continue;
            }
            if (i + 1 == period) {
                // 초기값: 첫 period개의 SMA
                BigDecimal initial = sma(closes.subList(0, period), period);
                result.add(initial);
                prevEma = initial;
                continue;
            }
            BigDecimal close = closes.get(i);
            BigDecimal curEma = close.subtract(prevEma).multiply(multiplier).add(prevEma)
                    .setScale(4, RoundingMode.HALF_UP);
            result.add(curEma);
            prevEma = curEma;
        }
        return result;
    }

    /**
     * 전체 구간에 대한 SMA 시계열을 반환한다 (앞쪽 period-1개는 null로 패딩).
     * 골든/데드크로스 판정을 위해 직전 값과 비교가 필요할 때 사용한다.
     */
    public static List<BigDecimal> smaSeries(List<BigDecimal> closes, int period) {
        List<BigDecimal> result = new java.util.ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            if (i + 1 < period) {
                result.add(null);
            } else {
                result.add(sma(closes.subList(0, i + 1), period));
            }
        }
        return result;
    }

    /**
     * 표준 RSI(Wilder 방식 단순화 버전, period 기본 14).
     * 데이터가 부족하면 null.
     */
    public static BigDecimal rsi(List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) {
            return null;
        }

        List<BigDecimal> window = closes.subList(closes.size() - (period + 1), closes.size());

        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;

        for (int i = 1; i < window.size(); i++) {
            BigDecimal diff = window.get(i).subtract(window.get(i - 1));
            if (diff.signum() > 0) {
                gainSum = gainSum.add(diff);
            } else {
                lossSum = lossSum.add(diff.abs());
            }
        }

        BigDecimal avgGain = gainSum.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        BigDecimal avgLoss = lossSum.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);

        if (avgLoss.signum() == 0) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal rs = avgGain.divide(avgLoss, 6, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 6, RoundingMode.HALF_UP));

        return rsi.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 직전 N일(마지막 캔들 제외) 중 최고가.
     */
    public static BigDecimal recentHigh(List<BigDecimal> highs, int lookback) {
        if (highs.size() <= lookback) {
            return null;
        }
        List<BigDecimal> window = highs.subList(highs.size() - 1 - lookback, highs.size() - 1);
        return window.stream().max(BigDecimal::compareTo).orElse(null);
    }

    /**
     * 직전 N일(마지막 캔들 제외) 중 최저가.
     */
    public static BigDecimal recentLow(List<BigDecimal> lows, int lookback) {
        if (lows.size() <= lookback) {
            return null;
        }
        List<BigDecimal> window = lows.subList(lows.size() - 1 - lookback, lows.size() - 1);
        return window.stream().min(BigDecimal::compareTo).orElse(null);
    }

    /**
     * 직전 N일 평균 거래량 (마지막 캔들 제외).
     */
    public static BigDecimal recentAvgVolume(List<BigDecimal> volumes, int lookback) {
        if (volumes.size() <= lookback) {
            return null;
        }
        List<BigDecimal> window = volumes.subList(volumes.size() - 1 - lookback, volumes.size() - 1);
        BigDecimal sum = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(lookback), 4, RoundingMode.HALF_UP);
    }
}
