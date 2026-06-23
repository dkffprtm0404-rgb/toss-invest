package com.tossinvest.tossinvestbackend.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 그리드서치 실행기. 종목 유니버스 × 파라미터 그리드 전체에 대해 백테스트를 실행하고,
 * 파라미터 조합별 종목 평균 성과를 집계해 종합 리포트를 만든다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestRunner {

    private final BacktestEngine engine;
    private final CandleRepository candleRepository;

    private static final int MIN_CANDLES = 100; // 캔들 100개 미만 종목 제외 (데이터 부족)
    private static final int MIN_TRADES  = 10;  // 거래 10건 미만은 통계 의미 없음

    public GridSearchReport runFullGrid(List<String> symbols) {
        long startTime = System.currentTimeMillis();
        List<BacktestParams> grid = BacktestParamGrid.generate();
        log.info("[그리드서치] 시작: 종목 {}개 x 파라미터 {}개 = {}회 시뮬레이션",
                symbols.size(), grid.size(), symbols.size() * grid.size());

        Map<String, List<CandleEntity>> candlesBySymbol = symbols.stream()
                .collect(Collectors.toMap(s -> s, candleRepository::findBySymbolOrderByTimestampAsc));

        // MIN_CANDLES 이상인 종목만 사전계산 (데이터 부족 종목 자동 제외)
        Map<String, BacktestEngine.PrecomputedIndicators> indMap = candlesBySymbol.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_CANDLES)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> engine.precompute(e.getValue())));

        log.info("[그리드서치] 유효 종목 {}개 (캔들 {}개 이상)", indMap.size(), MIN_CANDLES);

        List<ParamGroupResult> paramResults = new ArrayList<>();
        for (BacktestParams params : grid) {
            List<BacktestResult> perSymbol = new ArrayList<>();
            for (String symbol : indMap.keySet()) {
                perSymbol.add(engine.runWithIndicators(symbol, indMap.get(symbol), params));
            }
            paramResults.add(aggregate(params, perSymbol));
        }

        // best: 총거래 MIN_TRADES 이상 조합 중 샤프비율 최고 (샤프가 가장 신뢰도 높은 지표)
        ParamGroupResult best = paramResults.stream()
                .filter(p -> p.totalTrades >= MIN_TRADES)
                .max(Comparator.comparing(p -> p.avgSharpe))
                .orElse(null);

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("[그리드서치] 완료: {}ms, best 거래수={}, best샤프={}",
                elapsedMs, best != null ? best.totalTrades : -1, best != null ? best.avgSharpe : "-");

        return new GridSearchReport(paramResults, best, symbols, elapsedMs);
    }

    private ParamGroupResult aggregate(BacktestParams params, List<BacktestResult> perSymbolResults) {
        int totalTrades = perSymbolResults.stream().mapToInt(BacktestResult::getTotalTrades).sum();

        if (totalTrades == 0) {
            return new ParamGroupResult(params, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, perSymbolResults);
        }

        BigDecimal weightedWinRateSum = BigDecimal.ZERO;
        BigDecimal cumReturnSum = BigDecimal.ZERO;
        BigDecimal mddMin = BigDecimal.ZERO;
        BigDecimal profitFactorSum = BigDecimal.ZERO;
        BigDecimal sharpeSum = BigDecimal.ZERO;
        int symbolsWithTrades = 0;

        for (BacktestResult r : perSymbolResults) {
            if (r.getTotalTrades() == 0) continue;
            symbolsWithTrades++;
            weightedWinRateSum = weightedWinRateSum.add(r.getWinRate().multiply(BigDecimal.valueOf(r.getTotalTrades())));
            cumReturnSum = cumReturnSum.add(r.getCumulativeReturn());
            mddMin = mddMin.min(r.getMaxDrawdown());
            profitFactorSum = profitFactorSum.add(r.getProfitFactor());
            sharpeSum = sharpeSum.add(r.getSharpeRatio());
        }

        BigDecimal avgWinRate = weightedWinRateSum.divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP);
        BigDecimal avgProfitFactor = symbolsWithTrades == 0 ? BigDecimal.ZERO
                : profitFactorSum.divide(BigDecimal.valueOf(symbolsWithTrades), 4, RoundingMode.HALF_UP);
        BigDecimal avgSharpe = symbolsWithTrades == 0 ? BigDecimal.ZERO
                : sharpeSum.divide(BigDecimal.valueOf(symbolsWithTrades), 4, RoundingMode.HALF_UP);

        return new ParamGroupResult(params, totalTrades, avgWinRate, cumReturnSum, mddMin, avgProfitFactor, avgSharpe, perSymbolResults);
    }

    /** 파라미터 조합 하나에 대한 전체 종목 집계 결과 */
    public static class ParamGroupResult {
        public final BacktestParams params;
        public final int totalTrades;
        public final BigDecimal avgWinRate;
        public final BigDecimal sumCumulativeReturn;
        public final BigDecimal worstMdd;
        public final BigDecimal avgProfitFactor;
        public final BigDecimal avgSharpe;
        public final List<BacktestResult> perSymbolResults;

        public ParamGroupResult(BacktestParams params, int totalTrades, BigDecimal avgWinRate,
                                 BigDecimal sumCumulativeReturn, BigDecimal worstMdd,
                                 BigDecimal avgProfitFactor, BigDecimal avgSharpe,
                                 List<BacktestResult> perSymbolResults) {
            this.params = params;
            this.totalTrades = totalTrades;
            this.avgWinRate = avgWinRate;
            this.sumCumulativeReturn = sumCumulativeReturn;
            this.worstMdd = worstMdd;
            this.avgProfitFactor = avgProfitFactor;
            this.avgSharpe = avgSharpe;
            this.perSymbolResults = perSymbolResults;
        }
    }

    public static class GridSearchReport {
        public final List<ParamGroupResult> allResults;
        public final ParamGroupResult best;
        public final List<String> symbols;
        public final long elapsedMs;

        public GridSearchReport(List<ParamGroupResult> allResults, ParamGroupResult best, List<String> symbols, long elapsedMs) {
            this.allResults = allResults;
            this.best = best;
            this.symbols = symbols;
            this.elapsedMs = elapsedMs;
        }
    }
}
