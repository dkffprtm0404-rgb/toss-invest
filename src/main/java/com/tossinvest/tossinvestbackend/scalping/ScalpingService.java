package com.tossinvest.tossinvestbackend.scalping;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 1분봉 단타 시뮬레이션 서비스 v2 개선판.
 *
 * [매수 신호 개선 - 기존 EMA 골든크로스 순간만 → 3가지 복합 조건으로 완화]
 *   A: EMA5 > EMA20 상태 + 직전봉 대비 상승 + 거래량 1.2배 이상
 *   B: RSI 30~65 구간 + 거래량 급등 (과매도 반등 초입)
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

    private static final BigDecimal VOL_MULT       = BigDecimal.valueOf(1.2);  // 완화: 1.5→1.2
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);
    private static final BigDecimal RSI_HIGH       = BigDecimal.valueOf(65);  // 확대: 50→65
    private static final BigDecimal REBOUND_MIN    = BigDecimal.valueOf(0.005);
    private static final BigDecimal TRAILING_TRIG  = BigDecimal.valueOf(0.01);
    private static final BigDecimal TAKE_PROFIT    = BigDecimal.valueOf(0.02);
    private static final BigDecimal STOP_LOSS      = BigDecimal.valueOf(-0.006);

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 1);
    private static final LocalTime FORCE_EXIT   = LocalTime.of(15, 20);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    // saveAll의 트랜잭션 커밋까지 직렬화해 수동 스캔과 예약 스캔의 중복 진입을 막는다.
    // 시세 조회 중에는 DB 트랜잭션을 열어 두지 않는다.
    public synchronized ScalpingResult tick() {
        LocalDateTime now  = LocalDateTime.now(MARKET_ZONE);
        LocalDateTime scanStartedAt = now;
        LocalTime     time = now.toLocalTime();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY
                || time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE))
            return new ScalpingResult(List.of(), List.of(), now);

        Map<String, ScalpingPosition> openPos = repo.findByStatus("OPEN").stream()
                .collect(Collectors.toMap(ScalpingPosition::getSymbol, p -> p));

        var targets = new LinkedHashSet<String>();
        if (time.isBefore(FORCE_EXIT)) targets.addAll(ScalpingUniverse.SYMBOLS);
        targets.addAll(openPos.keySet());
        List<String> symbols = new ArrayList<>(targets);
        if (symbols.isEmpty()) return new ScalpingResult(List.of(), List.of(), now);

        ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, symbols.size()));
        List<Future<CandleResponse>> futures;
        try {
            List<Callable<CandleResponse>> requests = symbols.stream()
                    .<Callable<CandleResponse>>map(sym -> () -> marketDataService.getCandles(sym, "1m", CANDLE_COUNT))
                    .toList();
            // awaitTermination 뒤 무제한 Future.get으로 멈추지 않도록 전체 조회 시간을 제한한다.
            futures = ex.invokeAll(requests, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScalpingResult(List.of(), List.of(), now);
        } finally {
            ex.shutdownNow();
        }

        // 조회 중 15:20을 지났다면 신규 진입 대신 청산한다.
        now = LocalDateTime.now(MARKET_ZONE);
        time = now.toLocalTime();

        List<ScalpingPosition> toSave = new ArrayList<>();
        List<String> bought = new ArrayList<>(), sold = new ArrayList<>();
        int evaluated = 0, unavailable = 0;

        for (int index = 0; index < symbols.size(); index++) {
            String sym = symbols.get(index);
            try {
                List<CandleResponse.Candle> candles = completedCandles(futures.get(index).get(), scanStartedAt);
                if (!fresh(candles, now)) {
                    unavailable++;
                    continue;
                }

                List<BigDecimal> closes  = extract(candles, CandleResponse.Candle::getClosePrice);
                BigDecimal price = closes.get(closes.size() - 1);
                if (price.signum() <= 0) throw new IllegalArgumentException("유효하지 않은 현재가");

                if (openPos.containsKey(sym)) {
                    evaluated++;
                    ScalpingPosition pos = openPos.get(sym);
                    BigDecimal ret = price.subtract(pos.getEntryPrice())
                            .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP);
                    if (ret.compareTo(pos.getPeakRate()) > 0) pos.setPeakRate(ret);

                    boolean trailingOn = pos.getPeakRate().compareTo(TRAILING_TRIG) >= 0;
                    String reason = null;
                    if (!time.isBefore(FORCE_EXIT))                        reason = "FORCE_EXIT";
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
                    if (candles.size() < EMA_LONG + 2) {
                        unavailable++;
                        continue;
                    }
                    List<BigDecimal> volumes = extract(candles, CandleResponse.Candle::getVolume);
                    BuyEval eval = evaluate(closes, volumes);
                    evaluated++;
                    if (eval.signal) {
                        ScalpingPosition pos = ScalpingPosition.builder()
                                .symbol(sym).entryTime(now).entryPrice(price)
                                .ema5AtEntry(eval.ema5)
                                .ema20AtEntry(eval.ema20)
                                .build();
                        toSave.add(pos);
                        bought.add(sym + "@" + price + "[" + eval.reason + "]");
                        log.info("[스캘핑] 매수: {} @ {} | {} | vol배수={}", sym, price, eval.reason, eval.volRatio);
                    }
                }
            } catch (Exception e) {
                unavailable++;
                log.warn("[스캘핑] {} 시세 처리 실패: {}", sym, failureReason(e));
            }
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        log.info("[스캘핑] 스캔 완료: 대상 {}개, 평가 {}개, 데이터 대기/오류 {}개, 매수 {}건, 매도 {}건",
                symbols.size(), evaluated, unavailable, bought.size(), sold.size());
        return new ScalpingResult(bought, sold, now);
    }

    /** 디버그도 실제 매매와 동일한 정렬·확정봉·신호 조건을 사용한다. */
    public Map<String, Object> debugSignal(String symbol) {
        try {
            LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
            var candles = completedCandles(marketDataService.getCandles(symbol, "1m", CANDLE_COUNT), now);
            if (!fresh(candles, now)) return Map.of("error", "최신 확정 1분봉을 기다립니다.");
            if (candles.size() < EMA_LONG + 2) return Map.of("error", "지표 계산에 확정 1분봉 22개가 필요합니다.");
            var closes = extract(candles, CandleResponse.Candle::getClosePrice);
            var eval = evaluate(closes, extract(candles, CandleResponse.Candle::getVolume));
            int last = candles.size() - 1;
            return Map.ofEntries(
                    Map.entry("symbol", symbol), Map.entry("candleCount", candles.size()),
                    Map.entry("latestTimestamp", candles.get(last).getTimestamp()),
                    Map.entry("currentPrice", closes.get(last)), Map.entry("prevPrice", closes.get(last - 1)),
                    Map.entry("ema5", eval.ema5), Map.entry("ema20", eval.ema20),
                    Map.entry("ema5_gt_ema20", eval.ema5.compareTo(eval.ema20) > 0),
                    Map.entry("rsi7", eval.rsi != null ? eval.rsi : "null"),
                    Map.entry("volRatio", String.format(Locale.ROOT, "%.2f", eval.volRatio)),
                    Map.entry("condA_EMA추세", eval.condA), Map.entry("condB_RSI반등", eval.condB),
                    Map.entry("condC_저점반등", eval.condC), Map.entry("buySignal", eval.signal));
        } catch (Exception e) {
            return Map.of("error", "시세 처리 실패 (" + failureReason(e) + ")");
        }
    }

    private List<CandleResponse.Candle> completedCandles(CandleResponse response, LocalDateTime now) {
        if (response == null || response.getResult() == null || response.getResult().getCandles() == null)
            return List.of();
        return response.getResult().getCandles().stream()
                .filter(candle -> candleTime(candle).isBefore(now.truncatedTo(ChronoUnit.MINUTES)))
                .sorted(Comparator.comparing(this::candleTime)).toList();
    }

    private LocalDateTime candleTime(CandleResponse.Candle candle) {
        return OffsetDateTime.parse(candle.getTimestamp()).atZoneSameInstant(MARKET_ZONE).toLocalDateTime();
    }

    private boolean fresh(List<CandleResponse.Candle> candles, LocalDateTime now) {
        if (candles.isEmpty()) return false;
        var latest = candleTime(candles.get(candles.size() - 1));
        return latest.toLocalDate().equals(now.toLocalDate()) && !latest.isBefore(now.minusMinutes(2));
    }

    private String failureReason(Exception exception) {
        Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
        return cause instanceof WebClientResponseException http ? "HTTP " + http.getStatusCode().value()
                : cause.getClass().getSimpleName();
    }

    private BuyEval evaluate(List<BigDecimal> closes, List<BigDecimal> volumes) {
        int n = closes.size();
        BigDecimal price    = closes.get(n - 1);
        BigDecimal prevPrice = closes.get(n - 2);

        BigDecimal curVol = volumes.get(n - 1);
        BigDecimal avgVol = TechnicalIndicatorCalculator.recentAvgVolume(volumes, 5);
        double volRatio = avgVol == null || avgVol.signum() <= 0 ? 0
                : curVol.divide(avgVol, 4, RoundingMode.HALF_UP).doubleValue();
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

        String reason = condA ? "A_EMA추세" : condB ? "B_RSI반등" : condC ? "C_저점반등" : "";
        return new BuyEval(condA || condB || condC, reason, volRatio, condA, condB, condC,
                e5.get(last), e20.get(last), rsi);
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

    private record BuyEval(boolean signal, String reason, double volRatio,
                           boolean condA, boolean condB, boolean condC,
                           BigDecimal ema5, BigDecimal ema20, BigDecimal rsi) {}

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
