package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * trading-rules.md(v2.1)의 매수/매도 규�1칙을 과거 캔들 데이터 전체에 적용해 가상 매매를 시뮬레이션한다.
 * TradeSignalService와 동일한 지표/판단 로직을 사용하지만, 실시간 API 호출이 아니라
 * 미리 수집된 캔들 배열의 인덱스를 슬라이딩하며 "그 시점까지의 데이터만 보이는" 상태로 평가한다.
 * 1.8 시장 지수 필터는 TradeSignalService와 동일하게 TODO(미구현) 상태로 본 백테스트에도 적용하지 않는다.
 * 4장 ON/OFF 스위치는 거래 기록을 시뮬레이션 중 직접 집계할 수 있으므로 본 엔진에서 함께 적용한다.
 */
@Component
@Slf4j
public class BacktestEngine {

    private static final int EMA_SHORT = 5;
    private static final int EMA_LONG = 20;
    private static final int RSI_PERIOD = 7;
    private static final int BREAKOUT_LOOKBACK = 5;
    private static final BigDecimal VOLUME_SURGE_MULTIPLIER = BigDecimal.valueOf(1.5);
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);
    private static final BigDecimal RSI_INVALIDATE_THRESHOLD = BigDecimal.valueOf(80);
    private static final int MIN_WARMUP = EMA_LONG + 1;

    /** ON/OFF 스위치 (4장): 최근 거래 수 / 승률 임계값 / 거래일 수 / 손익률 임계값. v2.1 기본값 사용, 그리드 대상은 아님(8.1 별도 검증). */
    private static final int ON_OFF_LOOKBACK_TRADES = 20;
    private static final BigDecimal ON_OFF_MIN_WIN_RATE = BigDecimal.valueOf(0.35);
    private static final int ON_OFF_LOOKBACK_DAYS = 10;
    private static final BigDecimal ON_OFF_MIN_CUM_RETURN = BigDecimal.valueOf(-0.07);

    /**
     * 단일 종목, 단일 파라미터 조합에 대한 백테스트 실행.
     */
    public BacktestResult run(String symbol, List<CandleEntity> candles, BacktestParams params) {
        List<BigDecimal> closes = extract(candles, CandleEntity::getClosePrice);
        List<BigDecimal> highs = extract(candles, CandleEntity::getHighPrice);
        List<BigDecimal> lows = extract(candles, CandleEntity::getLowPrice);
        List<BigDecimal> opens = extract(candles, CandleEntity::getOpenPrice);
        List<BigDecimal> volumes = extract(candles, CandleEntity::getVolume);

        List<BacktestTrade> trades = new ArrayList<>();
        List<BacktestTrade> closedTrades = new ArrayList<>(); // ON/OFF 판단용 누적

        Position position = null; // 현재 보유 포지션 (단일 포지션만 시뮬레이션, symbol 단위 백테스트라 충분)

        for (int i = MIN_WARMUP; i < candles.size(); i++) {
            BigDecimal currentPrice = closes.get(i);
            long currentDayIndex = i;

            if (position != null) {
                position.holdingDays = (int) (currentDayIndex - position.entryIndex);
                BigDecimal changeRate = currentPrice.subtract(position.avgPrice)
                        .divide(position.avgPrice, 6, RoundingMode.HALF_UP);
                position.peakRate = position.peakRate.max(changeRate);

                BigDecimal trailingStartThreshold = position.holdingDays > params.getTrailingRelaxAfterDays()
                        ? params.getTrailingRelaxedThreshold()
                        : params.getTrailingStartThreshold();
                boolean trailingEverTriggered = position.peakRate.compareTo(trailingStartThreshold) >= 0;

                String exitReason = null;

                // 3.1 손절 - 최우선
                if (changeRate.compareTo(params.getStopLossRate()) <= 0) {
                    exitReason = "STOP_LOSS";
                }
                // 3.4 시간 기반 강제매도
                else if (position.holdingDays > params.getTimeExitHoldingDays() && !trailingEverTriggered) {
                    exitReason = "TIME_EXIT";
                }
                // 3.2 트레일링 스탑
                else {
                    BigDecimal trailingStopRate = calculateTrailingStopRate(position.peakRate, trailingStartThreshold);
                    if (trailingStopRate != null && changeRate.compareTo(trailingStopRate) < 0) {
                        exitReason = "TRAILING_STOP";
                    }
                }
                // 3.3 추세 전환 (데드크로스)
                if (exitReason == null && i >= 1) {
                    BigDecimal prevEma5 = TechnicalIndicatorCalculator.ema(closes.subList(0, i), EMA_SHORT);
                    BigDecimal prevEma20 = TechnicalIndicatorCalculator.ema(closes.subList(0, i), EMA_LONG);
                    BigDecimal curEma5 = TechnicalIndicatorCalculator.ema(closes.subList(0, i + 1), EMA_SHORT);
                    BigDecimal curEma20 = TechnicalIndicatorCalculator.ema(closes.subList(0, i + 1), EMA_LONG);
                    if (prevEma5 != null && prevEma20 != null && curEma5 != null && curEma20 != null
                            && prevEma5.compareTo(prevEma20) >= 0 && curEma5.compareTo(curEma20) < 0) {
                        exitReason = "DEAD_CROSS";
                    }
                }

                if (exitReason != null) {
                    BacktestTrade trade = position.close(currentDayIndex, currentPrice, exitReason);
                    trades.add(trade);
                    closedTrades.add(trade);
                    position = null;
                }
            }

            if (position == null) {
                boolean onOffAllowsBuy = checkOnOffSwitch(closedTrades, candles, i);

                if (onOffAllowsBuy) {
                    List<BigDecimal> closesUpToNow = closes.subList(0, i + 1);
                    List<BigDecimal> highsUpToNow = highs.subList(0, i + 1);
                    List<BigDecimal> lowsUpToNow = lows.subList(0, i + 1);
                    List<BigDecimal> opensUpToNow = opens.subList(0, i + 1);
                    List<BigDecimal> volumesUpToNow = volumes.subList(0, i + 1);

                    BuySignalEval eval = evaluateBuy(closesUpToNow, highsUpToNow, lowsUpToNow, opensUpToNow, volumesUpToNow, i, params);
                    if (eval.buySignal) {
                        position = new Position(i, currentPrice);
                    }
                }
            }
        }

        // 백테스트 종료 시점에 미청산 포지션이 있으면 마지막 종가로 강제 청산(MARK_TO_MARKET)해 통계에 포함
        if (position != null) {
            BacktestTrade trade = position.close(candles.size() - 1, closes.get(closes.size() - 1), "END_OF_DATA");
            trades.add(trade);
        }

        return BacktestResult.from(symbol, params, trades);
    }

    /**
     * 4장 ON/OFF 스위치: 최근 N거래 승률 또는 최근 M거래일 누적손익이 임계값 미달이면 신규매수 중단.
     */
    private boolean checkOnOffSwitch(List<BacktestTrade> closedTrades, List<CandleEntity> candles, int currentIndex) {
        if (closedTrades.size() >= ON_OFF_LOOKBACK_TRADES) {
            List<BacktestTrade> recent = closedTrades.subList(closedTrades.size() - ON_OFF_LOOKBACK_TRADES, closedTrades.size());
            long wins = recent.stream().filter(t -> t.returnRate.signum() > 0).count();
            BigDecimal winRate = BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(recent.size()), 6, RoundingMode.HALF_UP);
            if (winRate.compareTo(ON_OFF_MIN_WIN_RATE) < 0) {
                return false;
            }
        }
        // 최근 N거래일 누적손익: 최근 청산된 거래들 중 exitIndex가 lookback 범위 안에 드는 것만 합산
        BigDecimal cumReturn = BigDecimal.ZERO;
        for (int j = closedTrades.size() - 1; j >= 0; j--) {
            BacktestTrade t = closedTrades.get(j);
            if (currentIndex - t.exitIndex > ON_OFF_LOOKBACK_DAYS) break;
            cumReturn = cumReturn.add(t.returnRate);
        }
        return cumReturn.compareTo(ON_OFF_MIN_CUM_RETURN) > 0;
    }

    private BuySignalEval evaluateBuy(List<BigDecimal> closes, List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> opens,
                                       List<BigDecimal> volumes, int i, BacktestParams params) {
        BigDecimal currentPrice = closes.get(closes.size() - 1);
        BigDecimal rsi = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        BigDecimal recentHigh = TechnicalIndicatorCalculator.recentHigh(highs.subList(0, highs.size() - 1), BREAKOUT_LOOKBACK);
        BigDecimal avgVolume = TechnicalIndicatorCalculator.recentAvgVolume(volumes.subList(0, volumes.size() - 1), BREAKOUT_LOOKBACK);
        BigDecimal currentVolume = volumes.get(volumes.size() - 1);

        boolean volumeSurge = avgVolume != null && avgVolume.signum() > 0
                && currentVolume.compareTo(avgVolume.multiply(VOLUME_SURGE_MULTIPLIER)) >= 0;

        List<BigDecimal> emaShortSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
        List<BigDecimal> emaLongSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
        boolean goldenCross = isGoldenCross(emaShortSeries, emaLongSeries);

        boolean breakoutWithVolume = recentHigh != null && currentPrice.compareTo(recentHigh) > 0 && volumeSurge;
        boolean rsiRebound = isRsiReboundFromOversold(closes);

        int score = 0;
        if (goldenCross) score += params.getWeightA();
        if (rsiRebound) score += params.getWeightB();
        if (breakoutWithVolume) score += params.getWeightC();

        BigDecimal volTarget = calculateVolatilityBreakoutTarget(opens, highs, lows, i);
        boolean breakoutTargetHit = volTarget != null && currentPrice.compareTo(volTarget) >= 0;
        if (breakoutTargetHit) score += params.getWeightD();

        boolean excluded = false;
        if (rsi != null && rsi.compareTo(RSI_INVALIDATE_THRESHOLD) >= 0) excluded = true;

        BigDecimal avgTradingValue = calculateRecentAvgTradingValue(closes, volumes, BREAKOUT_LOOKBACK);
        if (avgTradingValue != null && avgTradingValue.compareTo(params.getMinAvgTradingValueKrw()) < 0) excluded = true;

        BigDecimal gapUpRate = closes.size() >= 2
                ? opens.get(opens.size() - 1).subtract(closes.get(closes.size() - 2)).divide(closes.get(closes.size() - 2), 6, RoundingMode.HALF_UP)
                : null;
        if (gapUpRate != null && gapUpRate.compareTo(params.getGapUpInvalidateRate()) >= 0) excluded = true;

        BigDecimal daySurgeRate = closes.size() >= 2
                ? currentPrice.subtract(closes.get(closes.size() - 2)).divide(closes.get(closes.size() - 2), 6, RoundingMode.HALF_UP)
                : null;
        if (daySurgeRate != null && daySurgeRate.compareTo(params.getDaySurgeInvalidateRate()) >= 0) excluded = true;

        boolean buySignal = !excluded && score >= params.getBuyScoreThreshold();
        return new BuySignalEval(buySignal);
    }

    private BigDecimal calculateVolatilityBreakoutTarget(List<BigDecimal> opens, List<BigDecimal> highs, List<BigDecimal> lows, int i) {
        if (opens.size() < 2) return null;
        BigDecimal todayOpen = opens.get(opens.size() - 1);
        BigDecimal prevHigh = highs.get(highs.size() - 2);
        BigDecimal prevLow = lows.get(lows.size() - 2);
        return todayOpen.add(prevHigh.subtract(prevLow).multiply(BigDecimal.valueOf(0.5))).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateTrailingStopRate(BigDecimal peakRate, BigDecimal startThreshold) {
        if (peakRate.compareTo(startThreshold) < 0) return null;
        BigDecimal threshold = startThreshold;
        BigDecimal stopLine = BigDecimal.ZERO;
        BigDecimal step = BigDecimal.valueOf(0.03);
        while (peakRate.compareTo(threshold) >= 0) {
            threshold = threshold.add(step);
            stopLine = stopLine.add(step);
        }
        return stopLine.subtract(step);
    }

    private boolean isRsiReboundFromOversold(List<BigDecimal> closes) {
        if (closes.size() < RSI_PERIOD + 2) return false;
        BigDecimal rsiPrev = TechnicalIndicatorCalculator.rsi(closes.subList(0, closes.size() - 1), RSI_PERIOD);
        BigDecimal rsiCur = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        if (rsiPrev == null || rsiCur == null) return false;
        return rsiPrev.compareTo(RSI_OVERSOLD) <= 0 && rsiCur.compareTo(RSI_OVERSOLD) > 0;
    }

    private BigDecimal calculateRecentAvgTradingValue(List<BigDecimal> closes, List<BigDecimal> volumes, int lookback) {
        if (closes.size() <= lookback) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int start = closes.size() - 1 - lookback;
        int end = closes.size() - 1;
        for (int j = start; j < end; j++) {
            sum = sum.add(closes.get(j).multiply(volumes.get(j)));
        }
        return sum.divide(BigDecimal.valueOf(lookback), 2, RoundingMode.HALF_UP);
    }

    private boolean isGoldenCross(List<BigDecimal> shortSeries, List<BigDecimal> longSeries) {
        int last = shortSeries.size() - 1;
        if (last < 1) return false;
        BigDecimal prevShort = shortSeries.get(last - 1);
        BigDecimal prevLong = longSeries.get(last - 1);
        BigDecimal curShort = shortSeries.get(last);
        BigDecimal curLong = longSeries.get(last);
        if (prevShort == null || prevLong == null || curShort == null || curLong == null) return false;
        return prevShort.compareTo(prevLong) <= 0 && curShort.compareTo(curLong) > 0;
    }

    private List<BigDecimal> extract(List<CandleEntity> candles, java.util.function.Function<CandleEntity, BigDecimal> extractor) {
        List<BigDecimal> values = new ArrayList<>();
        for (CandleEntity c : candles) values.add(extractor.apply(c));
        return values;
    }

    private record BuySignalEval(boolean buySignal) {}

    /** 시뮬레이션 중인 단일 포지션 상태 */
    private static class Position {
        final int entryIndex;
        final BigDecimal avgPrice;
        BigDecimal peakRate = BigDecimal.ZERO;
        int holdingDays = 0;

        Position(int entryIndex, BigDecimal avgPrice) {
            this.entryIndex = entryIndex;
            this.avgPrice = avgPrice;
        }

        BacktestTrade close(long exitIndex, BigDecimal exitPrice, String exitReason) {
            BigDecimal returnRate = exitPrice.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP);
            return new BacktestTrade(entryIndex, (int) exitIndex, avgPrice, exitPrice, returnRate, holdingDays, exitReason);
        }
    }
}
