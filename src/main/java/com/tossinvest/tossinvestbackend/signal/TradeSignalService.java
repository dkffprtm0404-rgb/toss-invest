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
 * .docs/trading-rules.md (v2.1) 의 매매 규칙을 캔들 데이터에 적용해 신호를 판단한다.
 * 1단계(신호 생성)만 수행하며, 실주문은 발생시키지 않는다.
 * v2.1 핵심: 모호한 정성적 예외를 모두 제거하고 숫자/조건으로만 판단한다.
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
    private static final BigDecimal RSI_INVALIDATE_THRESHOLD = BigDecimal.valueOf(80); // 1.2 v2.1: 80 이상 예외없이 무효
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);
    private static final BigDecimal MIN_AVG_TRADING_VALUE_KRW = BigDecimal.valueOf(10_000_000_000L); // 1.4: 100억원
    private static final int BUY_SCORE_THRESHOLD = 5;
    private static final int BUY_SCORE_THRESHOLD_EXPERIMENTAL = 4; // 2.1: 4점 기준 병행 기록
    private static final BigDecimal TRAILING_TRIGGER_RATE = BigDecimal.valueOf(0.03);

    private final MarketDataService marketDataService;

    /**
     * 보유하지 않은 종목에 대한 매수 후보 판단 (점수제, v2.1).
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

        // 1.4 거래대금 필터: 직전 5일 평균 거래대금 = Σ(종가 × 거래량) / 5
        BigDecimal avgTradingValue = calculateRecentAvgTradingValue(closes, volumes, BREAKOUT_LOOKBACK);

        List<BigDecimal> emaShortSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
        List<BigDecimal> emaLongSeries = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
        boolean goldenCross = isGoldenCross(emaShortSeries, emaLongSeries);

        boolean breakoutWithVolume = recentHigh != null && currentPrice.compareTo(recentHigh) > 0 && volumeSurge;
        boolean rsiReboundFromOversold = isRsiReboundFromOversold(closes);

        List<String> matched = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int score = 0;

        if (goldenCross) {
            matched.add("A. 골든크로스 (EMA" + EMA_SHORT + " > EMA" + EMA_LONG + ") [+2]");
            score += 2;
        }
        if (rsiReboundFromOversold) {
            matched.add("B. RSI(" + RSI_PERIOD + ") 과매도 반등 [+1]");
            score += 1;
        }
        if (breakoutWithVolume) {
            matched.add("C. 거래량 동반 직전 " + BREAKOUT_LOOKBACK + "일 최고가 돌파 [+3]");
            score += 3;
        }
        BigDecimal volatilityTarget = calculateVolatilityBreakoutTarget(candles);
        boolean breakoutTargetHit = volatilityTarget != null && currentPrice.compareTo(volatilityTarget) >= 0;
        if (breakoutTargetHit) {
            matched.add("D. 변동성 돌파 목표가(" + volatilityTarget + ") 도달 [+2]");
            score += 2;
        }

        // 2.2 제외 조건 (v2.1: 전부 수치 기준, 예외 없음)
        if (rsi != null && rsi.compareTo(RSI_INVALIDATE_THRESHOLD) >= 0) {
            excluded.add("RSI(" + rsi + ") ≥ 80 - 매수 무효 (예외 없음)");
        }
        if (avgTradingValue != null && avgTradingValue.compareTo(MIN_AVG_TRADING_VALUE_KRW) < 0) {
            excluded.add("직전 5일 평균 거래대금(" + formatKrw(avgTradingValue) + ") < 100억원 - 매수 무효");
        }
        // TODO: 1.7 시장 지수(KOSPI/KOSDAQ) EMA20 + 기울기 필터 - 지수 시세 API 확인 후 연동 필요

        boolean buySignal = score >= BUY_SCORE_THRESHOLD && excluded.isEmpty();
        boolean experimentalBuySignal = score >= BUY_SCORE_THRESHOLD_EXPERIMENTAL && excluded.isEmpty();

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
            String expNote = experimentalBuySignal && excluded.isEmpty()
                    ? " (참고: 실험 임계값 4점 기준으로는 충족 - 2.1 병행기록용)"
                    : "";
            builder.signalType(SignalType.HOLD)
                    .summary((excluded.isEmpty()
                            ? "점수 미달 (" + score + "/" + BUY_SCORE_THRESHOLD + ")"
                            : "제외 조건 발생: " + String.join(", ", excluded)) + expNote);
        }

        return builder.build();
    }

    /**
     * 보유 중인 종목에 대한 매도(손절/트레일링스탑/추세전환/시간기반) 판단 (v2.1).
     * avgPrice: 평균 매수가
     * peakRateSinceBuy: 매수 이후 기록된 최고 수익률(없으면 null 가능, 이 경우 현재 수익률을 최고치로 간주)
     * holdingDays: 매수 후 경과 거래일수 (3.4 시간 기반 청산 판단에 사용, 모르면 null 가능 - 이 경우 3.4 미적용)
     */
    public TradeSignal evaluateForSell(String symbol, BigDecimal avgPrice, BigDecimal peakRateSinceBuy, Integer holdingDays) {
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

        boolean trailingEverTriggered = effectivePeakRate.compareTo(TRAILING_TRIGGER_RATE) >= 0;

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

        // 3.4 시간 기반 강제매도: 5거래일 초과 + 트레일링 한 번도 미발동 시 예외없이 매도 (v2.1)
        if (holdingDays != null && holdingDays > 5 && !trailingEverTriggered) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_TREND_REVERSAL)
                    .currentPrice(currentPrice)
                    .matchedConditions(List.of("3.4 보유 " + holdingDays + "거래일 초과 + 트레일링 미발동 - 시간 기반 강제매도"))
                    .summary("시간 기반 강제매도: " + holdingDays + "거래일 경과, 트레일링 미발동")
                    .build();
        }

        // 3.2 트레일링 스탑
        BigDecimal trailingStopRate = calculateTrailingStopRate(effectivePeakRate);
        BigDecimal trailingStopPrice = avgPrice.multiply(BigDecimal.ONE.add(trailingStopRate))
                .setScale(2, RoundingMode.HALF_UP);

        // 3.4 보조: 3일 초과 + 트레일링 미발동 시 트레일링 시작 기준을 +3%에서 +1.5%로 낮춤
        BigDecimal effectiveTrailingTrigger = (holdingDays != null && holdingDays > 3 && !trailingEverTriggered)
                ? BigDecimal.valueOf(0.015)
                : TRAILING_TRIGGER_RATE;

        if (trailingStopRate != null && changeRate.compareTo(trailingStopRate) < 0
                && effectivePeakRate.compareTo(effectiveTrailingTrigger) >= 0) {
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

        // 3.3 추세 전환
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
                        + (trailingEverTriggered
                            ? " (트레일링 스탑 기준선 " + percentString(trailingStopRate) + ")"
                            : ""))
                .build();
    }

    /**
     * trading-rules.md 3.2 트레일링 스탑 구간표. peakRate 기준 +3%p 간격으로 기준선 산출.
     * +3% 미도달 시 null.
     */
    private BigDecimal calculateTrailingStopRate(BigDecimal peakRate) {
        if (peakRate.compareTo(TRAILING_TRIGGER_RATE) < 0) {
            return null;
        }
        BigDecimal threshold = TRAILING_TRIGGER_RATE;
        BigDecimal stopLine = BigDecimal.ZERO;
        BigDecimal step = BigDecimal.valueOf(0.03);

        while (peakRate.compareTo(threshold) >= 0) {
            threshold = threshold.add(step);
            stopLine = stopLine.add(step);
        }
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
     * 1.4 거래대금 필터: 직전 N일(마지막 캔들 제외) 평균 거래대금 = Σ(종가 × 거래량) / N
     */
    private BigDecimal calculateRecentAvgTradingValue(List<BigDecimal> closes, List<BigDecimal> volumes, int lookback) {
        if (closes.size() <= lookback) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int start = closes.size() - 1 - lookback;
        int end = closes.size() - 1;
        for (int i = start; i < end; i++) {
            sum = sum.add(closes.get(i).multiply(volumes.get(i)));
        }
        return sum.divide(BigDecimal.valueOf(lookback), 2, RoundingMode.HALF_UP);
    }

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

    private String formatKrw(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(100_000_000), 1, RoundingMode.HALF_UP) + "억원";
    }
}
