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

    /**
     * 전체 그리드서치 실행. 종목별 캔들은 미리 DB에서 한 번씩만 로드해 재사용한다(지표 재계산 비용 절감 목적은
     * 아니고 - 그리드 조합마다 지표를 다시 계산하긴 하지만 - 최소한 DB 조회/엔티티 변환 비용은 한 번만 든다).
     */
    public GridSearchReport runFullGrid(List<String> symbols) {
        long startTime = System.currentTimeMillis();
        List<BacktestParams> grid = BacktestParamGrid.generate();
        log.info("[그리드서치] 시작: 종목 {}개 x 파라미터 {}개 = {}회 시뮬레이션", symbols.size(), grid.size(), symbols.size() * grid.size());

        Map<String, List<CandleEntity>> candlesBySymbol = symbols.stream()
                .collect(Collectors.toMap(s -> s, candleRepository::findBySymbolOrderByTimestampAsc));

        List<ParamGroupResult> paramResults = new ArrayList<>();

        for (BacktestParams params : grid) {
            List<BacktestResult> perSymbolResults = new ArrayList<>();
            for (String symbol : symbols) {
                List<CandleEntity> candles = candlesBySymbol.get(symbol);
                if (candles == null || candles.size() < 21) continue; // 최소 워밍업 데이터 없으면 스킵
                perSymbolResults.add(engine.run(symbol, candles, params));
            }
            paramResults.add(aggregate(params, perSymbolResults));
        }

        BacktestResult baselinePerSymbol = null; // 참고용, 리포트에서는 grid 내 baseline 라벨로 찾음
        ParamGroupResult best = paramResults.stream()
                .filter(p -> p.totalTrades >= 5) // 거래수가 너무 적으면 통계적으로 의미 없음
                .max(Comparator.comparing(p -> p.avgWinRate.add(p.avgProfitFactor)))
                .orElse(null);

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("[그리드서치] 완료: {}ms 소요, 최적조합 거래수={}", elapsedMs, best != null ? best.totalTrades : -1);

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
