package com.tossinvest.tossinvestbackend.scalping;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 1분봉 단타 시뮬레이션 서비스 v2 개선판.
 *
 * [매수 신호 개선 - 기존 EMA 골든크로스 순간만 → 3가지 복합 조건으로 완화]
 *   A: EMA5 > EMA20 상태 + 직전봉 대비 상승 + 거래량 1.5배 이상
 *   B: RSI 30~50 구간 + 거래량 급등 (과매도 반등 초입)
 *   C: 직전 3봉 최저가 대비 +0.5% 이상 반등 + 거래량 급등
 *
 * [청산 개선]
 *   - peakRate 추적 추가
 *   - +1% 도달 시 손절선을 본전(0%)으로 올림 (트레일링)
 *   - +2% 도달 시 즉시 익절
 *   - 트레일링 전: 손절 -0.6%
 *   - 15:20 강제청산 유지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScalpingService {

    private final MarketDataService marketDataService;
    private final ScalpingPositionRepository repo;

    private static final int EMA_SHORT   = 5;
    private static final int EMA_LONG    = 20;
    private static final int RSI_PERIOD  = 7;
    private static final int CANDLE_COUNT = 50;
    private static final int THREADS     = 5;

    private static final BigDecimal VOL_MULT       = BigDecimal.valueOf(1.5);
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);
    private static final BigDecimal RSI_HIGH       = BigDecimal.valueOf(50);
    private static final BigDecimal REBOUND_MIN    = BigDecimal.valueOf(0.005);
    private static final BigDecimal TRAILING_TRIG  = BigDecimal.valueOf(0.01);
    private static final BigDecimal TAKE_PROFIT    = BigDecimal.valueOf(0.02);
    private static final BigDecimal STOP_LOSS      = BigDecimal.valueOf(-0.006);

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 1);
    private static final LocalTime FORCE_EXIT   = LocalTime.of(15, 20);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);

    @Transactional
    public ScalpingResult tick() {
        LocalDateTime now  = LocalDateTime.now();
        LocalTime     time = now.toLocalTime();
        if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) return ScalpingResult.empty();

        Map<String, ScalpingPosition> openPos = repo.findByStatus("OPEN").stream()
                .collect(Collectors.toMap(ScalpingPosition::getSymbol, p -> p));

        ExecutorService ex = Executors.newFixedThreadPool(THREADS);
        Map<String, Future<CandleResponse>> futures = new LinkedHashMap<>();
        for (String sym : ScalpingUniverse.SYMBOLS)
            futures.put(sym, ex.submit(() -> marketDataService.getCandles(sym, "1m", CANDLE_COUNT)));
        ex.shutdown();
        try { ex.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        List<ScalpingPosition> toSave = new ArrayList<>();
        List<String> bought = new ArrayList<>(), sold = new ArrayList<>();

        for (Map.Entry<String, Future<CandleResponse>> entry : futures.entrySet()) {
            String sym = entry.getKey();
            try {
                CandleResponse resp = entry.getValue().get();
                if (resp == null || resp.getResult() == null
                        || resp.getResult().getCandles() == null
                        || resp.getResult().getCandles().size() < EMA_LONG + 2) continue;

                List<CandleResponse.Candle> candles = resp.getResult().getCandles();
                List<BigDecimal> closes  = extract(candles, CandleResponse.Candle::getClosePrice);
                List<BigDecimal> volumes = extract(candles, CandleResponse.Candle::getVolume);
                BigDecimal price = closes.get(closes.size() - 1);

                if (openPos.containsKey(sym)) {
                    ScalpingPosition pos = openPos.get(sym);
                    BigDecimal ret = price.subtract(pos.getEntryPrice())
                            .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP);
                    if (ret.compareTo(pos.getPeakRate()) > 0) pos.setPeakRate(ret);

                    boolean trailingOn = pos.getPeakRate().compareTo(TRAILING_TRIG) >= 0;
                    String reason = null;
                    if (time.isAfter(FORCE_EXIT))                          reason = "FORCE_EXIT";
                    else if (ret.compareTo(TAKE_PROFIT) >= 0)              reason = "TAKE_PROFIT(+2%)";
                    else if (trailingOn && ret.compareTo(BigDecimal.ZERO) <= 0) reason = "TRAILING_STOP(본전)";
                    else if (!trailingOn && ret.compareTo(STOP_LOSS) <= 0) reason = "STOP_LOSS(-0.6%)";

                    if (reason != null) {
                        pos.setStatus("CLOSED"); pos.setExitTime(now);
                        pos.setExitPrice(price); pos.setReturnRate(ret); pos.setExitReason(reason);
                        toSave.add(pos);
                        sold.add(sym + String.format("(%+.2f%%,%s)", ret.doubleValue() * 100, reason));
                        log.info("[스캘핑] 매도: {} @ {} | {} | ret={}%", sym, price, reason,
                                String.format("%.3f", ret.doubleValue() * 100));
                    } else {
                        toSave.add(pos);
                    }
                } else if (time.isBefore(FORCE_EXIT)) {
                    BuyEval eval = evaluate(closes, volumes);
                    if (eval.signal) {
                        List<BigDecimal> e5  = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
                        List<BigDecimal> e20 = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
                        ScalpingPosition pos = ScalpingPosition.builder()
                                .symbol(sym).entryTime(now).entryPrice(price)
                                .ema5AtEntry(e5.get(e5.size()-1))
                                .ema20AtEntry(e20.get(e20.size()-1))
                                .build();
                        toSave.add(pos);
                        bought.add(sym + "@" + price + "[" + eval.reason + "]");
                        log.info("[스캘핑] 매수: {} @ {} | {} | vol배수={:.2f}", sym, price, eval.reason, eval.volRatio);
                    }
                }
            } catch (Exception e) {
                log.warn("[스캘핑] {} 에러: {}", sym, e.getMessage());
            }
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        return new ScalpingResult(bought, sold, now);
    }

    private BuyEval evaluate(List<BigDecimal> closes, List<BigDecimal> volumes) {
        int n = closes.size();
        BigDecimal price    = closes.get(n - 1);
        BigDecimal prevPrice = closes.get(n - 2);

        BigDecimal curVol = volumes.get(n - 1);
        BigDecimal avgVol = TechnicalIndicatorCalculator.recentAvgVolume(volumes.subList(0, n - 1), 5);
        if (avgVol == null || avgVol.signum() == 0) return BuyEval.no();
        double volRatio = curVol.divide(avgVol, 4, RoundingMode.HALF_UP).doubleValue();
        boolean volSurge = volRatio >= VOL_MULT.doubleValue();

        List<BigDecimal> e5  = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
        List<BigDecimal> e20 = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
        int last = e5.size() - 1;

        boolean condA = e5.get(last).compareTo(e20.get(last)) > 0
                && price.compareTo(prevPrice) > 0 && volSurge;

        BigDecimal rsi = TechnicalIndicatorCalculator.rsi(closes, RSI_PERIOD);
        boolean condB = rsi != null && rsi.compareTo(RSI_LOW) >= 0
                && rsi.compareTo(RSI_HIGH) <= 0 && volSurge;

        BigDecimal low3 = closes.subList(n - 4, n - 1).stream()
                .min(BigDecimal::compareTo).orElse(price);
        BigDecimal rebound = price.subtract(low3).divide(low3.max(BigDecimal.ONE), 6, RoundingMode.HALF_UP);
        boolean condC = rebound.compareTo(REBOUND_MIN) >= 0 && volSurge;

        if (condA) return new BuyEval(true, "A_EMA추세", volRatio);
        if (condB) return new BuyEval(true, "B_RSI반등", volRatio);
        if (condC) return new BuyEval(true, "C_저점반등", volRatio);
        return BuyEval.no();
    }

    private List<BigDecimal> extract(List<CandleResponse.Candle> candles, Function<CandleResponse.Candle, String> fn) {
        return candles.stream().map(c -> new BigDecimal(fn.apply(c))).toList();
    }

    public ScalpingStats getStats() {
        List<ScalpingPosition> open   = repo.findByStatus("OPEN");
        List<ScalpingPosition> closed = repo.findClosedOrderByExitTimeDesc();
        int total  = closed.size();
        long wins  = closed.stream().filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
        double winRate   = total == 0 ? 0 : (double) wins / total;
        double cumReturn = closed.stream().filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue()).sum();
        Map<String, Long> byReason = closed.stream().filter(p -> p.getExitReason() != null)
                .collect(Collectors.groupingBy(ScalpingPosition::getExitReason, Collectors.counting()));
        return new ScalpingStats(open, closed.stream().limit(50).toList(), total, winRate, cumReturn, byReason);
    }

    private record BuyEval(boolean signal, String reason, double volRatio) {
        static BuyEval no() { return new BuyEval(false, "", 0); }
    }

    public record ScalpingResult(List<String> bought, List<String> sold, LocalDateTime at) {
        public static ScalpingResult empty() { return new ScalpingResult(List.of(), List.of(), LocalDateTime.now()); }
    }

    public record ScalpingStats(
        List<ScalpingPosition> openPositions,
        List<ScalpingPosition> closedPositions,
        int totalTrades, double winRate, double cumReturn,
        Map<String, Long> exitReasonCounts
    ) {}
}