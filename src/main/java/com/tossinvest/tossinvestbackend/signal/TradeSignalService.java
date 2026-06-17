package com.tossinvest.tossinvestbackend.signal;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * .docs/trading-rules.md (v2.0) 의 매매 규칙을 캔들 데이터에 적용해 신호를 판단한다.
 * 1단계(신호 생성)만 수행하며, 실주문은 발생시키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TradeSignalService {

    private static final int EMA_SHORT = 5;
    private static final int EMA_LONG = 20;
    private static final int RSI_PERIOD = 7;
    private static final int BREAKOUT_LOOKBACK = 5;
    private static final BigDecimal VOLUME_SURGE_MULTIPLIER = BigDecimal.valueOf(1.5);
    private static final BigDecimal STOP_LOSS_RATE = BigDecimal.valueOf(-0.02);
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);
    private static final int BUY_SCORE_THRESHOLD = 5;

    private final MarketDataService marketDataService;

    /**
     * 보유하지 않은 종목에 대한 매수 후보 판단 (점수제, v2.0).
     */
    public TradeSignal evaluateForBuy(String symbol) {
        CandleResponse candleResponse = marketDataService.getCandles(symbol, "1d", 60);
        List<CandleResponse.Candle> candles = sortedAscending(candleResponse);

        if (candles.size() < EMA_LONG + 1) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.HOLD)
                    .summary("캔들 데이터 부족으로 판단 불가 (최소 " + (EMA_LONG + 1) + "개 필요, 현재 " + candles.size() + "개)")
                    .build();
        }

        List<BigDecimal> closes = extract(candles, CandleResponse.Candle::getClosePrice);
        List<BigDecimal> highs = extract(candles, CandleResponse.Candle::getHighPrice);
        List<BigDecimal> lows = extract(candles, CandleResponse.Candle::getLowPrice);
        List<BigDecimal> volumes = extract(candles, CandleResponse.Candle::getVolume);

        BigDecimal currentPrice = closes.get(closes.size() - 1);
        BigDecimal ema5 = TechnicalIndicatorCalculator.ema(closes, EMA_SHORT);
        BigDecimal ema20 = TechnicalIndicatorCalculator.ema(closes, EMA_LONG);
        BigDecimal rsi = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        BigDecimal recentHigh = TechnicalIndicatorCalculator.recentHigh(highs, BREAKOUT_LOOKBACK);
        BigDecimal recentLow = TechnicalIndicatorCalculator.recentLow(lows, BREAKOUT_LOOKBACK);
        BigDecimal avgVolume = TechnicalIndicatorCalculator.recentAvgVolume(volumes, BREAKOUT_LOOKBACK);
        BigDecimal currentVolume = volumes.get(volumes.size() - 1);

        boolean volumeSurge = avgVolume != null && avgVolume.signum() > 0 && currentVolume != null
                && currentVolume.compareTo(avgVolume.multiply(VOLUME_SURGE_MULTIPLIER)) >= 0;

        List<BigDecimal> emaShortSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
        List<BigDecimal> emaLongSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
        boolean goldenCross = isGoldenCross(emaShortSeries, emaLongSeries);

        boolean breakoutWithVolume = recentHigh != null && currentPrice.compareTo(recentHigh) > 0 && volumeSurge;

        boolean rsiReboundFromOversold = isRsiReboundFromOversold(closes);

        List<String> matched = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int score = 0;

        // A. 골든크로스 - 2점
        if (goldenCross) {
            matched.add("A. 골든크로스 (EMA" + EMA_SHORT + " > EMA" + EMA_LONG + ") [+2]");
            score += 2;
        }
        // B. RSI 과매도 반등 - 1점
        if (rsiReboundFromOversold) {
            matched.add("B. RSI(" + RSI_PERIOD + ") 과매도 반등 [+1]");
            score += 1;
        }
        // C. 거래량 동반 돌파 - 3점 (가장 강한 신호)
        if (breakoutWithVolume) {
            matched.add("C. 거래량 동반 직전 " + BREAKOUT_LOOKBACK + "일 최고가 돌파 [+3]");
            score += 3;
        }
        // D. 변동성 돌파 - 2점
        BigDecimal volatilityTarget = calculateVolatilityBreakoutTarget(candles);
        boolean breakoutTargetHit = volatilityTarget != null && currentPrice.compareTo(volatilityTarget) >= 0;
        if (breakoutTargetHit) {
            matched.add("D. 변동성 돌파 목표가(" + volatilityTarget + ") 도달 [+2]");
            score += 2;
        }

        if (rsi != null && rsi.compareTo(RSI_OVERBOUGHT) >= 0) {
            excluded.add("RSI 과매수 구간(" + rsi + ") - 매수 후보 제외");
        }
        // TODO: 1.6 시장 지수(KOSPI/KOSDAQ) EMA20 필터 - 지수 시세 API 확인 후 추가 연동 필요

        boolean buySignal = score >= BUY_SCORE_THRESHOLD && excluded.isEmpty();

        TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                .symbol(symbol)
                .currentPrice(currentPrice)
                .ema5(ema5)
                .ema20(ema20)
                .rsi7(rsi)
                .recentHigh5(recentHigh)
                .recentLow5(recentLow)
                .volumeSurge(volumeSurge)
                .score(score)
                .scoreThreshold(BUY_SCORE_THRESHOLD)
                .matchedConditions(matched)
                .excludedReasons(excluded);

        if (buySignal) {
            builder.signalType(SignalType.BUY_CANDIDATE)
                    .summary("매수 후보: 점수 " + score + "/" + BUY_SCORE_THRESHOLD + " 이상 충족");
        } else {
            builder.signalType(SignalType.HOLD)
                    .summary(excluded.isEmpty()
                            ? "점수 미달 (" + score + "/" + BUY_SCORE_THRESHOLD + ")"
                            : "제외 조건 발생: " + String.join(", ", excluded));
        }

        return builder.build();
    }

    /**
     * 보유 중인 종목에 대한 매도(손절/트레일링스탑/추세전환) 판단 (v2.0).
     * avgPrice: 평균 매수가, peakRateSinceBuy: 매수 이후 기록된 최고 수익률(없으면 null 또는 현재 수익률 전달).
     */
    public TradeSignal evaluateForSell(String symbol, BigDecimal avgPrice, BigDecimal peakRateSinceBuy) {
        CandleResponse candleResponse = marketDataService.getCandles(symbol, "1d", 60);
        List<CandleResponse.Candle> candles = sortedAscending(candleResponse);

        if (candles.isEmpty()) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.HOLD)
                    .summary("캔들 데이터 없음")
                    .build();
        }

        List<BigDecimal> closes = extract(candles, CandleResponse.Candle::getClosePrice);
        List<BigDecimal> lows = extract(candles, CandleResponse.Candle::getLowPrice);
        BigDecimal currentPrice = closes.get(closes.size() - 1);

        BigDecimal changeRate = avgPrice.signum() == 0
                ? BigDecimal.ZERO
                : currentPrice.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP);

        BigDecimal effectivePeakRate = peakRateSinceBuy == null
                ? changeRate
                : changeRate.max(peakRateSinceBuy);

        // 3.1 손절 - 최우선, 예외 없음
        if (changeRate.compareTo(STOP_LOSS_RATE) <= 0) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_STOP_LOSS)
                    .currentPrice(currentPrice)
                    .matchedConditions(List.of("3.1 손절 기준 도달 (" + percentString(changeRate) + ")"))
                    .summary("손절 신호: 평단가 대비 " + percentString(changeRate))
                    .build();
        }

        // 3.2 트레일링 스탑 계산
        BigDecimal trailingStopRate = calculateTrailingStopRate(effectivePeakRate);
        BigDecimal trailingStopPrice = avgPrice.multiply(BigDecimal.ONE.add(trailingStopRate))
                .setScale(2, RoundingMode.HALF_UP);

        if (trailingStopRate != null && changeRate.compareTo(trailingStopRate) < 0
                && effectivePeakRate.compareTo(BigDecimal.valueOf(0.03)) >= 0) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_TAKE_PROFIT)
                    .currentPrice(currentPrice)
                    .trailingStopPrice(trailingStopPrice)
                    .matchedConditions(List.of(
                            "3.2 트레일링 스탑 발동 (최고 " + percentString(effectivePeakRate)
                                    + " → 기준선 " + percentString(trailingStopRate)
                                    + " 하회, 현재 " + percentString(changeRate) + ")"
                    ))
                    .summary("트레일링 스탑 매도 신호")
                    .build();
        }

        // 3.3 추세 전환 (데드크로스 또는 직전 5일 최저가 하향 돌파)
        if (closes.size() >= EMA_LONG + 1) {
            List<BigDecimal> emaShortSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
            List<BigDecimal> emaLongSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
            if (isDeadCross(emaShortSeries, emaLongSeries)) {
                return TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(SignalType.SELL_TREND_REVERSAL)
                        .currentPrice(currentPrice)
                        .matchedConditions(List.of("3.3 데드크로스 발생"))
                        .summary("추세전환 매도 신호: 데드크로스")
                        .build();
            }
        }

        BigDecimal recentLow = TechnicalIndicatorCalculator.recentLow(lows, BREAKOUT_LOOKBACK);
        if (recentLow != null && currentPrice.compareTo(recentLow) < 0) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_TREND_REVERSAL)
                    .currentPrice(currentPrice)
                    .matchedConditions(List.of("3.3 직전 " + BREAKOUT_LOOKBACK + "일 최저가 하향 돌파"))
                    .summary("추세전환 매도 신호: 최근 저점 하향 돌파")
                    .build();
        }

        return TradeSignal.builder()
                .symbol(symbol)
                .signalType(SignalType.HOLD)
                .currentPrice(currentPrice)
                .trailingStopPrice(trailingStopPrice)
                .summary("보유 유지: 평단가 대비 " + percentString(changeRate)
                        + (effectivePeakRate.compareTo(BigDecimal.valueOf(0.03)) >= 0
                            ? " (트레일링 스탑 기준선 " + percentString(trailingStopRate) + ")"
                            : ""))
                .build();
    }

    /**
     * trading-rules.md 3.2 트레일링 스탑 구간표를 적용한다.
     * peakRate(매수 이후 최고 수익률)에 따라 손절 기준선(수익률 기준)을 반환.
     * +3% 미도달 시 null (트레일링 미발동, 3.1 고정 손절만 적용).
     */
    private BigDecimal calculateTrailingStopRate(BigDecimal peakRate) {
        if (peakRate.compareTo(BigDecimal.valueOf(0.03)) < 0) {
            return null;
        }
        // 임계값들: peak가 3,5,8,11,14...% 도달마다 기준선이 0,2,5,8,11...%로 따라감 (+3%p 간격)
        BigDecimal threshold = BigDecimal.valueOf(0.03);
        BigDecimal stopLine = BigDecimal.ZERO;
        BigDecimal step = BigDecimal.valueOf(0.03);

        while (peakRate.compareTo(threshold) >= 0) {
            threshold = threshold.add(step);
            stopLine = stopLine.add(step);
        }
        // 마지막에 한 단계 초과했으므로 한 칸 되돌림
        return stopLine.subtract(step);
    }

    private boolean isRsiReboundFromOversold(List<BigDecimal> closes) {
        if (closes.size() < RSI_PERIOD + 2) {
            return false;
        }
        BigDecimal rsiPrev = TechnicalIndicatorCalculator.rsi(closes.subList(0, closes.size() - 1), RSI_PERIOD);
        BigDecimal rsiCur = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        if (rsiPrev == null || rsiCur == null) {
            return false;
        }
        return rsiPrev.compareTo(RSI_OVERSOLD) <= 0 && rsiCur.compareTo(RSI_OVERSOLD) > 0;
    }

    /**
     * 1.5 변동성 돌파 목표가 = 당일 시가 + (직전일 고가-저가) * K(0.5)
     */
    private BigDecimal calculateVolatilityBreakoutTarget(List<CandleResponse.Candle> candles) {
        if (candles.size() < 2) return null;
        CandleResponse.Candle today = candles.get(candles.size() - 1);
        CandleResponse.Candle prevDay = candles.get(candles.size() - 2);

        BigDecimal todayOpen = new BigDecimal(today.getOpenPrice());
        BigDecimal prevHigh = new BigDecimal(prevDay.getHighPrice());
        BigDecimal prevLow = new BigDecimal(prevDay.getLowPrice());
        BigDecimal k = BigDecimal.valueOf(0.5);

        return todayOpen.add(prevHigh.subtract(prevLow).multiply(k)).setScale(2, RoundingMode.HALF_UP);
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

    private boolean isDeadCross(List<BigDecimal> shortSeries, List<BigDecimal> longSeries) {
        int last = shortSeries.size() - 1;
        if (last < 1) return false;
        BigDecimal prevShort = shortSeries.get(last - 1);
        BigDecimal prevLong = longSeries.get(last - 1);
        BigDecimal curShort = shortSeries.get(last);
        BigDecimal curLong = longSeries.get(last);
        if (prevShort == null || prevLong == null || curShort == null || curLong == null) return false;
        return prevShort.compareTo(prevLong) >= 0 && curShort.compareTo(curLong) < 0;
    }

    private List<CandleResponse.Candle> sortedAscending(CandleResponse response) {
        List<CandleResponse.Candle> candles = new ArrayList<>(
                response.getResult() != null && response.getResult().getCandles() != null
                        ? response.getResult().getCandles()
                        : List.of()
        );
        // API는 최신순(내림차순)으로 내려주므로 오래된 것 -> 최신 순으로 뒤집는다.
        java.util.Collections.reverse(candles);
        return candles;
    }

    private List<BigDecimal> extract(List<CandleResponse.Candle> candles,
                                      java.util.function.Function<CandleResponse.Candle, String> extractor) {
        List<BigDecimal> values = new ArrayList<>();
        for (CandleResponse.Candle candle : candles) {
            String raw = extractor.apply(candle);
            values.add(raw == null ? BigDecimal.ZERO : new BigDecimal(raw));
        }
        return values;
    }

    private String percentString(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }
}
