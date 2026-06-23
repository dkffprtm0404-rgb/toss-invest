package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final CandleCollectionService collectionService;
    private final CandleCsvImportService csvImportService;
    private final BacktestRunner runner;
    private final CandleRepository candleRepository;
    private final BacktestEngine engine;
    private final MarketDataService marketDataService;

    /**
     * pykrx로 생성한 CSV를 읽어 DB에 적재한다.
     * csvPath: 서버 기준 절대경로 또는 상대경로 (기본값: 프로젝트 루트의 scripts/candles.csv)
     */
    @PostMapping("/import-csv")
    public CandleCsvImportService.ImportResult importCsv(
            @RequestParam(defaultValue = "scripts/candles.csv") String csvPath) {
        return csvImportService.importFromCsv(csvPath);
    }

    /**
     * 백테스트 유니버스 종목들의 일봉 캔들을 토스 API에서 수집한다.
     * before 파라미터 문제로 1페이지(최신 200개)만 실질적으로 동작한다.
     * 2~3년치가 필요하면 scripts/fetch_candles.py + /import-csv 사용 권장.
     */
    @PostMapping("/collect")
    public Map<String, Integer> collectCandles(@RequestParam(defaultValue = "650") int targetDays) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (String symbol : BacktestUniverse.all()) {
            int collected = collectionService.collectDailyCandles(symbol, targetDays);
            result.put(symbol, collected);
        }
        return result;
    }

    /** 현재 DB에 저장된 종목별 캔들 개수를 확인한다. */
    @GetMapping("/candle-status")
    public Map<String, Long> candleStatus() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        for (String symbol : BacktestUniverse.all()) {
            result.put(symbol, candleRepository.countBySymbol(symbol));
        }
        return result;
    }

    /** 전체 그리드서치 실행. 시간이 걸릴 수 있으므로 동기 호출이며 완료 후 결과를 반환한다. */
    @PostMapping("/run")
    public BacktestReportView runGrid() {
        BacktestRunner.GridSearchReport report = runner.runFullGrid(BacktestUniverse.all());
        return BacktestReportView.from(report);
    }

    /** 단일 종목 + v2.1 기본값(베이스라인)으로만 빠르게 1회 실행해본다 (검증용). */
    @GetMapping("/run-baseline")
    public BacktestResult runBaselineForSymbol(@RequestParam String symbol) {
        List<CandleEntity> candles = candleRepository.findBySymbolOrderByTimestampAsc(symbol);
        return engine.run(symbol, candles, BacktestParamGrid.baseline());
    }

    /** 단일 종목 + 그리드서치 최적 파라미터로 실행해 베이스라인과 직접 비교. */
    @GetMapping("/run-optimal")
    public BacktestResult runOptimalForSymbol(@RequestParam String symbol) {
        List<CandleEntity> candles = candleRepository.findBySymbolOrderByTimestampAsc(symbol);
        return engine.run(symbol, candles, BacktestParamGrid.optimal());
    }

    /** 전체 유니버스 베이스라인 vs 최적값 비교 요약. */
    @GetMapping("/compare")
    public Map<String, Object> compare() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<String> symbols = BacktestUniverse.all();
        Map<String, List<CandleEntity>> candleMap = symbols.stream()
                .filter(s -> candleRepository.countBySymbol(s) >= 100)
                .collect(java.util.stream.Collectors.toMap(s -> s, candleRepository::findBySymbolOrderByTimestampAsc));

        BacktestParams baseline = BacktestParamGrid.baseline();
        BacktestParams optimal  = BacktestParamGrid.optimal();

        double bWinSum = 0, bSharpeSum = 0, bPFSum = 0;
        double oWinSum = 0, oSharpeSum = 0, oPFSum = 0;
        int bTotal = 0, oTotal = 0;

        for (Map.Entry<String, List<CandleEntity>> e : candleMap.entrySet()) {
            BacktestResult b = engine.run(e.getKey(), e.getValue(), baseline);
            BacktestResult o = engine.run(e.getKey(), e.getValue(), optimal);
            bTotal += b.getTotalTrades(); bWinSum += b.getWinRate().doubleValue() * b.getTotalTrades();
            bSharpeSum += b.getSharpeRatio().doubleValue(); bPFSum += b.getProfitFactor().doubleValue();
            oTotal += o.getTotalTrades(); oWinSum += o.getWinRate().doubleValue() * o.getTotalTrades();
            oSharpeSum += o.getSharpeRatio().doubleValue(); oPFSum += o.getProfitFactor().doubleValue();
        }
        int n = candleMap.size();
        result.put("baseline", Map.of("totalTrades", bTotal, "avgWinRate", bTotal > 0 ? bWinSum/bTotal : 0,
                "avgSharpe", bSharpeSum/n, "avgProfitFactor", bPFSum/n));
        result.put("optimal",  Map.of("totalTrades", oTotal, "avgWinRate", oTotal > 0 ? oWinSum/oTotal : 0,
                "avgSharpe", oSharpeSum/n, "avgProfitFactor", oPFSum/n));
        result.put("optimalParams", Map.of("buyScoreThreshold", 4, "timeExitHoldingDays", 10, "gapUpInvalidateRate", 0.15,
                "stopLossRate", -0.02, "weightA", 2, "weightB", 1, "weightC", 3, "weightD", 2));
        return result;
    }

    /**
     * before 날짜 직접 지정 테스트용 디버그 엔드포인트.
     * before는 "YYYY-MM-DD" 형식 (예: "2025-06-01").
     */
    @GetMapping("/debug-candles")
    public CandleResponse debugCandles(
            @RequestParam String symbol,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "5") int count
    ) {
        if (before == null) {
            return marketDataService.getCandles(symbol, "1d", count);
        }
        return marketDataService.getCandlesWithDateBefore(symbol, "1d", count, before);
    }
}
