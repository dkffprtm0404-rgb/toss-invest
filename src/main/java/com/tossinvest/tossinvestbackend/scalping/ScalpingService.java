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
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 1분봉 단타 시뮬레이션 서비스.
 * v2.1 신호 규칙(EMA 골든크로스 + 거래량 돌파)을 1분봉에 적용한다.
 * 청산 기준: 목표가 +2% or +3% / 손절 -0.6% / 당일 15:20 강제청산
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScalpingService {

    private final MarketDataService marketDataService;
    private final ScalpingPositionRepository repo;

    // ── 파라미터 ───────────────────────────────────────────────────────────────
    private static final int    EMA_SHORT        = 5;
    private static final int    EMA_LONG         = 20;
    private static final int    CANDLE_COUNT     = 30;  // 1분봉 30개 (EMA20 계산에 충분)
    private static final BigDecimal VOL_MULT     = BigDecimal.valueOf(1.5);
    private static final BigDecimal TAKE_PROFIT1 = BigDecimal.valueOf(0.02);  // +2%
    private static final BigDecimal TAKE_PROFIT2 = BigDecimal.valueOf(0.03);  // +3%
    private static final BigDecimal STOP_LOSS    = BigDecimal.valueOf(-0.006); // -0.6%
    private static final LocalTime  FORCE_EXIT   = LocalTime.of(15, 20);
    private static final LocalTime  MARKET_OPEN  = LocalTime.of(9, 1);
    private static final int        THREADS      = 5;

    /**
     * 매 1분 호출. 보유 포지션 청산 판단 → 신규 매수 신호 판단 순서로 실행.
     */
    @Transactional
    public ScalpingResult tick() {
        LocalDateTime now = LocalDateTime.now();
        LocalTime time = now.toLocalTime();

        // 장 외 시간이면 스킵
        if (time.isBefore(MARKET_OPEN) || time.isAfter(LocalTime.of(15, 35))) {
            return ScalpingResult.empty();
        }

        Map<String, ScalpingPosition> openPos = repo.findByStatus("OPEN").stream()
                .collect(Collectors.toMap(ScalpingPosition::getSymbol, p -> p));

        List<ScalpingPosition> toSave = new ArrayList<>();
        List<String> bought = new ArrayList<>(), sold = new ArrayList<>();

        // ── 병렬 캔들 조회 ────────────────────────────────────────────────────
        ExecutorService ex = Executors.newFixedThreadPool(THREADS);
        Map<String, Future<CandleResponse>> futures = new LinkedHashMap<>();
        for (String sym : ScalpingUniverse.SYMBOLS) {
            futures.put(sym, ex.submit(() ->
                    marketDataService.getCandles(sym, "1m", CANDLE_COUNT)));
        }
        ex.shutdown();
        try { ex.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        for (Map.Entry<String, Future<CandleResponse>> entry : futures.entrySet()) {
            String sym = entry.getKey();
            try {
                CandleResponse resp = entry.getValue().get();
                if (resp == null || resp.getResult() == null
                        || resp.getResult().getCandles() == null
                        || resp.getResult().getCandles().size() < EMA_LONG + 1) continue;

                List<CandleResponse.Candle> candles = resp.getResult().getCandles();
                List<BigDecimal> closes  = candles.stream().map(c -> new BigDecimal(c.getClosePrice())).toList();
                List<BigDecimal> volumes = candles.stream().map(c -> new BigDecimal(c.getVolume())).toList();
                BigDecimal currentPrice  = closes.get(closes.size() - 1);

                // ── 매도 판단 (보유 포지션) ────────────────────────────────────
                if (openPos.containsKey(sym)) {
                    ScalpingPosition pos = openPos.get(sym);
                    BigDecimal ret = currentPrice.subtract(pos.getEntryPrice())
                            .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP);

                    String exitReason = null;
                    if (time.isAfter(FORCE_EXIT)) {
                        exitReason = "FORCE_EXIT";
                    } else if (ret.compareTo(STOP_LOSS) <= 0) {
                        exitReason = "STOP_LOSS";
                    } else if (ret.compareTo(TAKE_PROFIT2) >= 0) {
                        exitReason = "TAKE_PROFIT_3";
                    } else if (ret.compareTo(TAKE_PROFIT1) >= 0) {
                        exitReason = "TAKE_PROFIT_2";
                    }

                    if (exitReason != null) {
                        pos.setStatus("CLOSED");
                        pos.setExitTime(now);
                        pos.setExitPrice(currentPrice);
                        pos.setReturnRate(ret);
                        pos.setExitReason(exitReason);
                        toSave.add(pos);
                        sold.add(sym + "(" + String.format("%+.2f%%", ret.doubleValue() * 100) + "," + exitReason + ")");
                        log.info("[스캘핑] 매도: {} @ {} | {} | {:.2f}%", sym, currentPrice, exitReason, ret.doubleValue() * 100);
                    }
                }

                // ── 매수 판단 (포지션 없는 종목) ──────────────────────────────
                else if (time.isBefore(FORCE_EXIT) && isBuySignal(closes, volumes)) {
                    List<BigDecimal> e5  = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
                    List<BigDecimal> e20 = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
                    ScalpingPosition pos = ScalpingPosition.builder()
                            .symbol(sym)
                            .entryTime(now)
                            .entryPrice(currentPrice)
                            .ema5AtEntry(e5.get(e5.size() - 1))
                            .ema20AtEntry(e20.get(e20.size() - 1))
                            .build();
                    toSave.add(pos);
                    bought.add(sym + "@" + currentPrice);
                    log.info("[스캘핑] 매수: {} @ {}", sym, currentPrice);
                }

            } catch (Exception e) {
                log.warn("[스캘핑] {} 처리 에러: {}", sym, e.getMessage());
            }
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        return new ScalpingResult(bought, sold, now);
    }

    /** v2.1 매수 신호 판단 (1분봉 기준) */
    private boolean isBuySignal(List<BigDecimal> closes, List<BigDecimal> volumes) {
        if (closes.size() < EMA_LONG + 1) return false;

        List<BigDecimal> e5  = TechnicalIndicatorCalculator.emaSeries(closes, EMA_SHORT);
        List<BigDecimal> e20 = TechnicalIndicatorCalculator.emaSeries(closes, EMA_LONG);
        int last = e5.size() - 1;
        if (last < 1) return false;

        // A: 골든크로스 (이번 봉에서 EMA5가 EMA20을 상향 돌파)
        boolean goldenCross = e5.get(last - 1).compareTo(e20.get(last - 1)) <= 0
                && e5.get(last).compareTo(e20.get(last)) > 0;

        // C: 거래량 동반 (직전 5개 봉 평균의 1.5배 이상)
        BigDecimal currentVol = volumes.get(volumes.size() - 1);
        BigDecimal avgVol = TechnicalIndicatorCalculator.recentAvgVolume(
                volumes.subList(0, volumes.size() - 1), 5);
        boolean volSurge = avgVol != null && avgVol.signum() > 0
                && currentVol.compareTo(avgVol.multiply(VOL_MULT)) >= 0;

        // D: 변동성 돌파 (직전 봉 고가-저가의 0.5배 + 현재 봉 시가)
        // 1분봉에서는 골든크로스 + 거래량만으로도 충분 (D는 보조)
        return goldenCross && volSurge;
    }

    public ScalpingStats getStats() {
        List<ScalpingPosition> open   = repo.findByStatus("OPEN");
        List<ScalpingPosition> closed = repo.findClosedOrderByExitTimeDesc();
        int total = closed.size();
        long wins = closed.stream().filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
        double winRate = total == 0 ? 0 : (double) wins / total;
        double cumReturn = closed.stream().filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue()).sum();
        return new ScalpingStats(open, closed.stream().limit(50).toList(), total, winRate, cumReturn);
    }

    public record ScalpingResult(List<String> bought, List<String> sold, LocalDateTime at) {
        public static ScalpingResult empty() {
            return new ScalpingResult(List.of(), List.of(), LocalDateTime.now());
        }
    }
    public record ScalpingStats(List<ScalpingPosition> openPositions, List<ScalpingPosition> closedPositions,
                                 int totalTrades, double winRate, double cumReturn) {}
}
