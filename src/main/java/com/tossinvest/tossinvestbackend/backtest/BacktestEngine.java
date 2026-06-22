package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class BacktestEngine {

    private static final int EMA_SHORT = 5;
    private static final int EMA_LONG  = 20;
    private static final int RSI_PERIOD = 7;
    private static final int LOOKBACK   = 5;
    private static final BigDecimal VOL_SURGE_MULT = BigDecimal.valueOf(1.5);
    private static final BigDecimal RSI_OVERSOLD   = BigDecimal.valueOf(30);
    private static final BigDecimal RSI_INVALIDATE = BigDecimal.valueOf(80);
    private static final int MIN_WARMUP = EMA_LONG + 2;

    private static final int    ONOFF_TRADES  = 20;
    private static final double ONOFF_MIN_WIN = 0.35;
    private static final int    ONOFF_DAYS    = 10;
    private static final double ONOFF_MIN_CUM = -0.07;

    static class PrecomputedIndicators {
        final BigDecimal[] closes, opens, highs, lows, volumes;
        final BigDecimal[] ema5, ema20, rsi;
        final BigDecimal[] avgVolume5, recentHigh5, avgTradingValue5, volatilityTarget;
        final int n;

        PrecomputedIndicators(List<CandleEntity> candles) {
            n = candles.size();
            closes  = new BigDecimal[n]; opens  = new BigDecimal[n];
            highs   = new BigDecimal[n]; lows   = new BigDecimal[n];
            volumes = new BigDecimal[n];
            for (int i = 0; i < n; i++) {
                closes[i]  = candles.get(i).getClosePrice();
                opens[i]   = candles.get(i).getOpenPrice();
                highs[i]   = candles.get(i).getHighPrice();
                lows[i]    = candles.get(i).getLowPrice();
                volumes[i] = candles.get(i).getVolume();
            }
            List<BigDecimal> cl = List.of(closes);
            ema5  = TechnicalIndicatorCalculator.emaSeries(cl, EMA_SHORT).toArray(new BigDecimal[0]);
            ema20 = TechnicalIndicatorCalculator.emaSeries(cl, EMA_LONG).toArray(new BigDecimal[0]);
            rsi   = new BigDecimal[n];
            for (int i = RSI_PERIOD + 1; i < n; i++)
                rsi[i] = TechnicalIndicatorCalculator.rsi(cl.subList(0, i + 1), RSI_PERIOD);
            avgVolume5 = new BigDecimal[n]; recentHigh5 = new BigDecimal[n];
            avgTradingValue5 = new BigDecimal[n]; volatilityTarget = new BigDecimal[n];
            for (int i = LOOKBACK; i < n; i++) {
                BigDecimal vs = BigDecimal.ZERO, mh = BigDecimal.ZERO, tv = BigDecimal.ZERO;
                for (int j = i - LOOKBACK; j < i; j++) {
                    vs = vs.add(volumes[j]);
                    if (highs[j].compareTo(mh) > 0) mh = highs[j];
                    tv = tv.add(closes[j].multiply(volumes[j]));
                }
                avgVolume5[i]       = vs.divide(BigDecimal.valueOf(LOOKBACK), 4, RoundingMode.HALF_UP);
                recentHigh5[i]      = mh;
                avgTradingValue5[i] = tv.divide(BigDecimal.valueOf(LOOKBACK), 2, RoundingMode.HALF_UP);
                if (i >= 1)
                    volatilityTarget[i] = opens[i]
                            .add(highs[i-1].subtract(lows[i-1]).multiply(BigDecimal.valueOf(0.5)))
                            .setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    public PrecomputedIndicators precompute(List<CandleEntity> candles) {
        return new PrecomputedIndicators(candles);
    }

    public BacktestResult run(String symbol, List<CandleEntity> candles, BacktestParams params) {
        return runWithIndicators(symbol, precompute(candles), params);
    }

    public BacktestResult runWithIndicators(String symbol, PrecomputedIndicators ind, BacktestParams params) {
        List<BacktestTrade> trades = new ArrayList<>(), closed = new ArrayList<>();
        Position pos = null;
        for (int i = MIN_WARMUP; i < ind.n; i++) {
            BigDecimal price = ind.closes[i];
            if (pos != null) {
                pos.holdingDays = i - pos.entryIndex;
                BigDecimal chg = price.subtract(pos.avgPrice).divide(pos.avgPrice, 6, RoundingMode.HALF_UP);
                if (chg.compareTo(pos.peakRate) > 0) pos.peakRate = chg;
                BigDecimal thr = pos.holdingDays > params.getTrailingRelaxAfterDays()
                        ? params.getTrailingRelaxedThreshold() : params.getTrailingStartThreshold();
                boolean triggered = pos.peakRate.compareTo(thr) >= 0;
                String reason = null;
                if (chg.compareTo(params.getStopLossRate()) <= 0) { reason = "STOP_LOSS"; }
                else if (pos.holdingDays > params.getTimeExitHoldingDays() && !triggered) { reason = "TIME_EXIT"; }
                else { BigDecimal ts = trailingStop(pos.peakRate, thr); if (ts != null && chg.compareTo(ts) < 0) reason = "TRAILING_STOP"; }
                if (reason == null && i >= 1 && ind.ema5[i-1] != null && ind.ema20[i-1] != null
                        && ind.ema5[i] != null && ind.ema20[i] != null
                        && ind.ema5[i-1].compareTo(ind.ema20[i-1]) >= 0 && ind.ema5[i].compareTo(ind.ema20[i]) < 0)
                    reason = "DEAD_CROSS";
                if (reason != null) { BacktestTrade t = pos.close(i, price, reason); trades.add(t); closed.add(t); pos = null; }
            }
            if (pos == null && checkOnOff(closed, i) && isBuy(ind, i, params))
                pos = new Position(i, price);
        }
        if (pos != null) trades.add(pos.close(ind.n - 1, ind.closes[ind.n - 1], "END_OF_DATA"));
        return BacktestResult.from(symbol, params, trades);
    }

    private boolean isBuy(PrecomputedIndicators ind, int i, BacktestParams params) {
        if (ind.avgVolume5[i] == null || ind.recentHigh5[i] == null) return false;
        BigDecimal price = ind.closes[i], rsi = ind.rsi[i];
        if (rsi != null && rsi.compareTo(RSI_INVALIDATE) >= 0) return false;
        if (ind.avgTradingValue5[i] != null && ind.avgTradingValue5[i].compareTo(params.getMinAvgTradingValueKrw()) < 0) return false;
        if (i >= 1) {
            BigDecimal gap = ind.opens[i].subtract(ind.closes[i-1]).divide(ind.closes[i-1], 6, RoundingMode.HALF_UP);
            if (gap.compareTo(params.getGapUpInvalidateRate()) >= 0) return false;
            BigDecimal surge = price.subtract(ind.closes[i-1]).divide(ind.closes[i-1], 6, RoundingMode.HALF_UP);
            if (surge.compareTo(params.getDaySurgeInvalidateRate()) >= 0) return false;
        }
        int score = 0;
        if (i >= 1 && ind.ema5[i-1] != null && ind.ema20[i-1] != null && ind.ema5[i] != null && ind.ema20[i] != null
                && ind.ema5[i-1].compareTo(ind.ema20[i-1]) <= 0 && ind.ema5[i].compareTo(ind.ema20[i]) > 0)
            score += params.getWeightA();
        if (i >= 1 && rsi != null && ind.rsi[i-1] != null
                && ind.rsi[i-1].compareTo(RSI_OVERSOLD) <= 0 && rsi.compareTo(RSI_OVERSOLD) > 0)
            score += params.getWeightB();
        if (ind.volumes[i].compareTo(ind.avgVolume5[i].multiply(VOL_SURGE_MULT)) >= 0 && price.compareTo(ind.recentHigh5[i]) > 0)
            score += params.getWeightC();
        if (ind.volatilityTarget[i] != null && price.compareTo(ind.volatilityTarget[i]) >= 0)
            score += params.getWeightD();
        return score >= params.getBuyScoreThreshold();
    }

    private boolean checkOnOff(List<BacktestTrade> closed, int idx) {
        if (closed.size() >= ONOFF_TRADES) {
            long wins = closed.subList(closed.size() - ONOFF_TRADES, closed.size()).stream().filter(t -> t.returnRate.signum() > 0).count();
            if ((double) wins / ONOFF_TRADES < ONOFF_MIN_WIN) return false;
        }
        double cum = 0;
        for (int j = closed.size() - 1; j >= 0; j--) {
            BacktestTrade t = closed.get(j); if (idx - t.exitIndex > ONOFF_DAYS) break;
            cum += t.returnRate.doubleValue();
        }
        return cum > ONOFF_MIN_CUM;
    }

    private BigDecimal trailingStop(BigDecimal peak, BigDecimal start) {
        if (peak.compareTo(start) < 0) return null;
        BigDecimal thr = start, stop = BigDecimal.ZERO, step = BigDecimal.valueOf(0.03);
        while (peak.compareTo(thr) >= 0) { thr = thr.add(step); stop = stop.add(step); }
        return stop.subtract(step);
    }

    private static class Position {
        final int entryIndex; final BigDecimal avgPrice;
        BigDecimal peakRate = BigDecimal.ZERO; int holdingDays = 0;
        Position(int e, BigDecimal p) { entryIndex = e; avgPrice = p; }
        BacktestTrade close(int exitIdx, BigDecimal exitPrice, String reason) {
            BigDecimal ret = exitPrice.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP);
            return new BacktestTrade(entryIndex, exitIdx, avgPrice, exitPrice, ret, holdingDays, reason);
        }
    }
}