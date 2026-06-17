package com.tossinvest.tossinvestbackend.signal;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * .docs/trading-rules.md 의 매매 규칙을 캔들 데이터에 적용해 신호를 판단한다.
 * 1단계(신호 생성)만 수행하며, 실주문은 발생시키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TradeSignalService {

    private static final int SMA_SHORT = 5;
    private static final int SMA_LONG = 20;
    private static final int RSI_PERIOD = 14;
    private static final int BREAKOUT_LOOKBACK = 5;
    private static final BigDecimal VOLUME_SURGE_MULTIPLIER = BigDecimal.valueOf(1.5);
    private static final BigDecimal STOP_LOSS_RATE = BigDecimal.valueOf(-0.02);
    private static final BigDecimal TAKE_PROFIT_MIN_RATE = BigDecimal.valueOf(0.03);
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);

    private final MarketDataService marketDataService;

    /**
     * 보유하지 않은 종목에 대한 매수 후보 판단.
     */
    public TradeSignal evaluateForBuy(String symbol) {
        CandleResponse candleResponse = marketDataService.getCandles(symbol, "1d", 60);
        List<CandleResponse.Candle> candles = sortedAscending(candleResponse);

        if (candles.size() < SMA_LONG + 1) {
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.HOLD)
                    .summary("캔들 데이터 부족으로 판단 불가 (최소 " + (SMA_LONG + 1) + "개 필요, 현재 " + candles.size() + "개)")
                    .build();
        }

        List<BigDecimal> closes = extract(candles, CandleResponse.Candle::getClosePrice);
        List<BigDecimal> highs = extract(candles, CandleResponse.Candle::getHighPrice);
        List<BigDecimal> lows = extract(candles, CandleResponse.Candle::getLowPrice);
        List<BigDecimal> volumes = extract(candles, CandleResponse.Candle::getVolume);

        BigDecimal currentPrice = closes.get(closes.size() - 1);
        BigDecimal sma5 = TechnicalIndicatorCalculator.sma(closes, SMA_SHORT);
        BigDecimal sma20 = TechnicalIndicatorCalculator.sma(closes, SMA_LONG);
        BigDecimal rsi = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        BigDecimal recentHigh = TechnicalIndicatorCalculator.recentHigh(highs, BREAKOUT_LOOKBACK);
        BigDecimal recentLow = TechnicalIndicatorCalculator.recentLow(lows, BREAKOUT_LOOKBACK);
        BigDecimal avgVolume = TechnicalIndicatorCalculator.recentAvgVolume(volumes, BREAKOUT_LOOKBACK);
        BigDecimal currentVolume = volumes.get(volumes.size() - 1);

        boolean volumeSurge = avgVolume != null && currentVolume != null
                && currentVolume.compareTo(avgVolume.multiply(VOLUME_SURGE_MULTIPLIER)) >= 0;

        List<BigDecimal> smaShortSeries = TechnicalIndicatorCalculator.smaSeries(closes, SMA_SHORT);
        List<BigDecimal> smaLongSeries = TechnicalIndicatorCalculator.smaSeries(closes, SMA_LONG);
        boolean goldenCross = isGoldenCross(smaShortSeries, smaLongSeries);

        boolean breakoutWithVolume = recentHigh != null && currentPrice.compareTo(recentHigh) > 0 && volumeSurge;

        List<String> matched = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        if (goldenCross) {
            matched.add("A. 골든크로스 발생 (SMA" + SMA_SHORT + " > SMA" + SMA_LONG + ")");
        }
        if (breakoutWithVolume) {
            matched.add("C. 거래량 동반 직전 " + BREAKOUT_LOOKBACK + "일 최고가 돌파");
        }
        // RSI 과매도 반등(B)은 일봉 RSI 추세 확인이 필요하므로 현 단계에서는 임계값 근접만 체크
        if (rsi != null && rsi.compareTo(RSI_OVERSOLD) <= 0) {
            matched.add("B(근접). RSI 과매도 구간 (" + rsi + ")");
        }

        if (rsi != null && rsi.compareTo(RSI_OVERBOUGHT) >= 0) {
            excluded.add("RSI 과매수 구간(" + rsi + ") - 매수 후보 제외");
        }

        boolean buySignal = matched.size() >= 2 && excluded.isEmpty();

        TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                .symbol(symbol)
                .currentPrice(currentPrice)
                .sma5(sma5)
                .sma20(sma20)
                .rsi14(rsi)
                .recentHigh5(recentHigh)
                .recentLow5(recentLow)
                .volumeSurge(volumeSurge)
                .matchedConditions(matched)
                .excludedReasons(excluded);

        if (buySignal) {
            builder.signalType(SignalType.BUY_CANDIDATE)
                    .summary("매수 후보: " + matched.size() + "개 조건 충족");
        } else {
            builder.signalType(SignalType.HOLD)
                    .summary(excluded.isEmpty()
                            ? "조건 충족 부족 (" + matched.size() + "/2)"
                            : "제외 조건 발생: " + String.join(", ", excluded));
        }

        return builder.build();
    }

    /**
     * 보유 중인 종목에 대한 매도(손절/익절/추세전환) 판단. avgPrice: 평균 매수가.
     */
    public TradeSignal evaluateForSell(String symbol, BigDecimal avgPrice) {
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
                : currentPrice.subtract(avgPrice).divide(avgPrice, 6, java.math.RoundingMode.HALF_UP);

        List<String> matched = new ArrayList<>();

        // 3.1 손절 - 최우선, 예외 없음
        if (changeRate.compareTo(STOP_LOSS_RATE) <= 0) {
            matched.add("3.1 손절 기준 도달 (" + percentString(changeRate) + ")");
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_STOP_LOSS)
                    .currentPrice(currentPrice)
                    .matchedConditions(matched)
                    .summary("손절 신호: 평단가 대비 " + percentString(changeRate))
                    .build();
        }

        // 3.3 추세 전환 (데드크로스 또는 직전 5일 최저가 하향 돌파)
        if (closes.size() >= SMA_LONG + 1) {
            List<BigDecimal> smaShortSeries = TechnicalIndicatorCalculator.smaSeries(closes, SMA_SHORT);
            List<BigDecimal> smaLongSeries = TechnicalIndicatorCalculator.smaSeries(closes, SMA_LONG);
            if (isDeadCross(smaShortSeries, smaLongSeries)) {
                matched.add("3.3 데드크로스 발생");
                return TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(SignalType.SELL_TREND_REVERSAL)
                        .currentPrice(currentPrice)
                        .matchedConditions(matched)
                        .summary("추세전환 매도 신호: 데드크로스")
                        .build();
            }
        }

        BigDecimal recentLow = TechnicalIndicatorCalculator.recentLow(lows, BREAKOUT_LOOKBACK);
        if (recentLow != null && currentPrice.compareTo(recentLow) < 0) {
            matched.add("3.3 직전 " + BREAKOUT_LOOKBACK + "일 최저가 하향 돌파");
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_TREND_REVERSAL)
                    .currentPrice(currentPrice)
                    .matchedConditions(matched)
                    .summary("추세전환 매도 신호: 최근 저점 하향 돌파")
                    .build();
        }

        // 3.2 익절
        if (changeRate.compareTo(TAKE_PROFIT_MIN_RATE) >= 0) {
            matched.add("3.2 익절 기준 도달 (" + percentString(changeRate) + ")");
            return TradeSignal.builder()
                    .symbol(symbol)
                    .signalType(SignalType.SELL_TAKE_PROFIT)
                    .currentPrice(currentPrice)
                    .matchedConditions(matched)
                    .summary("익절 신호: 평단가 대비 " + percentString(changeRate))
                    .build();
        }

        return TradeSignal.builder()
                .symbol(symbol)
                .signalType(SignalType.HOLD)
                .currentPrice(currentPrice)
                .summary("보유 유지: 평단가 대비 " + percentString(changeRate))
                .build();
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
        return rate.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }
}
