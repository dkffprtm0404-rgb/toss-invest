package com.tossinvest.tossinvestbackend.scalping;

import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.signal.TechnicalIndicatorCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scalping")
@RequiredArgsConstructor
public class ScalpingController {

    private final ScalpingService service;
    private final ScalpingPositionRepository repo;
    private final MarketDataService marketDataService;

    /** 지금 즉시 1분봉 단타 신호 평가 (수동 트리거) */
    @PostMapping("/tick")
    public ScalpingService.ScalpingResult tickNow() {
        return service.tick();
    }

    /** 통계 요약 (대시보드용) */
    @GetMapping("/stats")
    public ScalpingService.ScalpingStats stats() {
        return service.getStats();
    }

    /** 현재 보유 중인 가상 포지션 */
    @GetMapping("/positions")
    public List<ScalpingPosition> openPositions() {
        return repo.findByStatus("OPEN");
    }

    /** 최근 청산 기록 (최신 50건) */
    @GetMapping("/history")
    public List<ScalpingPosition> history() {
        return repo.findClosedOrderByExitTimeDesc();
    }

    /** 대상 종목 목록 확인 */
    @GetMapping("/universe")
    public List<String> universe() {
        return ScalpingUniverse.SYMBOLS;
    }

    /** 1분봉 캔들 + 지표 조회 디버그 (신호 판단 원인 파악용) */
    @GetMapping("/debug/{symbol}")
    public Object debugSignal(@PathVariable String symbol) {
        try {
            var resp = marketDataService.getCandles(symbol, "1m", 25);
            if (resp == null || resp.getResult() == null) return Map.of("error", "응답 없음");
            var candles = resp.getResult().getCandles();
            if (candles == null || candles.isEmpty()) return Map.of("error", "캔들 없음");

            var closes  = candles.stream().map(c -> new BigDecimal(c.getClosePrice())).toList();
            var volumes = candles.stream().map(c -> new BigDecimal(c.getVolume())).toList();

            var e5  = TechnicalIndicatorCalculator.emaSeries(closes, 5);
            var e20 = TechnicalIndicatorCalculator.emaSeries(closes, 20);
            var rsi = TechnicalIndicatorCalculator.rsi(closes, 7);

            int n = closes.size();
            BigDecimal curVol = volumes.get(n - 1);
            BigDecimal avgVol = TechnicalIndicatorCalculator.recentAvgVolume(volumes.subList(0, n - 1), 5);
            double volRatio = (avgVol != null && avgVol.signum() > 0)
                    ? curVol.divide(avgVol, 4, RoundingMode.HALF_UP).doubleValue() : 0;

            int last = e5.size() - 1;
            BigDecimal price     = closes.get(n - 1);
            BigDecimal prevPrice = closes.get(n - 2);

            boolean condA = e5.get(last).compareTo(e20.get(last)) > 0
                    && price.compareTo(prevPrice) > 0 && volRatio >= 1.5;
            boolean condB = rsi != null && rsi.compareTo(BigDecimal.valueOf(30)) >= 0
                    && rsi.compareTo(BigDecimal.valueOf(50)) <= 0 && volRatio >= 1.5;
            BigDecimal low3 = closes.subList(n - 4, n - 1).stream().min(BigDecimal::compareTo).orElse(price);
            BigDecimal rebound = price.subtract(low3).divide(low3.max(BigDecimal.ONE), 6, RoundingMode.HALF_UP);
            boolean condC = rebound.compareTo(BigDecimal.valueOf(0.005)) >= 0 && volRatio >= 1.5;

            return Map.ofEntries(
                Map.entry("symbol", symbol),
                Map.entry("candleCount", candles.size()),
                Map.entry("latestTimestamp", candles.get(0).getTimestamp()),
                Map.entry("currentPrice", price),
                Map.entry("prevPrice", prevPrice),
                Map.entry("ema5", e5.get(last)),
                Map.entry("ema20", e20.get(last)),
                Map.entry("ema5_gt_ema20", e5.get(last).compareTo(e20.get(last)) > 0),
                Map.entry("rsi7", rsi != null ? rsi : "null"),
                Map.entry("volRatio", String.format("%.2f", volRatio)),
                Map.entry("condA_EMA추세", condA),
                Map.entry("condB_RSI반등", condB),
                Map.entry("condC_저점반등", condC),
                Map.entry("buySignal", condA || condB || condC)
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
