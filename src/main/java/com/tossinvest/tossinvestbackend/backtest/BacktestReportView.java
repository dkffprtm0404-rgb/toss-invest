package com.tossinvest.tossinvestbackend.backtest;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 그리드서치 결과를 대시보드에서 바로 쓸 수 있도록 가공한 뷰.
 * 상위 조합 랭킹, v2.1 베이스라인과의 비교, 파라미터별 단변량 영향도(8장 항목들을 개별적으로 보기 위함)를 포함한다.
 */
@Getter
public class BacktestReportView {

    private List<String> symbols;
    private long elapsedMs;
    private int totalCombinations;
    private RowView baseline;
    private List<RowView> topByWinRate;
    private List<RowView> topByProfitFactor;
    private List<RowView> topBySharpe;
    private Map<String, List<FactorImpact>> factorImpacts; // 파라미터명 -> 값별 평균성과

    public static BacktestReportView from(BacktestRunner.GridSearchReport report) {
        BacktestReportView view = new BacktestReportView();
        view.symbols = report.symbols;
        view.elapsedMs = report.elapsedMs;
        view.totalCombinations = report.allResults.size();

        List<RowView> rows = report.allResults.stream()
                .filter(r -> r.totalTrades >= 5)
                .map(RowView::from)
                .collect(Collectors.toList());

        // 베이스라인(v2.1 본문 기본값)과 가장 가까운 조합 찾기 (label 비교)
        String baselineLabel = BacktestParamGrid.baseline().label();
        view.baseline = rows.stream()
                .filter(r -> r.label.equals(baselineLabel))
                .findFirst()
                .orElse(null);

        view.topByWinRate = rows.stream()
                .sorted(Comparator.comparing((RowView r) -> r.winRate).reversed())
                .limit(10)
                .collect(Collectors.toList());

        view.topByProfitFactor = rows.stream()
                .sorted(Comparator.comparing((RowView r) -> r.profitFactor).reversed())
                .limit(10)
                .collect(Collectors.toList());

        view.topBySharpe = rows.stream()
                .sorted(Comparator.comparing((RowView r) -> r.sharpeRatio).reversed())
                .limit(10)
                .collect(Collectors.toList());

        view.factorImpacts = buildFactorImpacts(report.allResults);

        return view;
    }

    /**
     * 각 파라미터를 하나씩 고정하고 나머지를 평균낸 "단변량 영향도"를 계산한다.
     * 예: 거래대금=20억인 모든 조합의 평균 승률/손익비 vs 30억 vs 50억.
     * 그리드서치 전체를 한 번에 돌렸어도, 이 집계를 통해 각 파라미터의 개별 영향을 분리해서 볼 수 있다.
     */
    private static Map<String, List<FactorImpact>> buildFactorImpacts(List<BacktestRunner.ParamGroupResult> all) {
        List<BacktestRunner.ParamGroupResult> valid = all.stream().filter(r -> r.totalTrades >= 5).collect(Collectors.toList());

        Map<String, List<FactorImpact>> result = new java.util.LinkedHashMap<>();
        result.put("거래대금필터", groupBy(valid, r -> formatKrw(r.params.getMinAvgTradingValueKrw())));
        result.put("갭상승필터", groupBy(valid, r -> percent(r.params.getGapUpInvalidateRate())));
        result.put("당일급등필터", groupBy(valid, r -> percent(r.params.getDaySurgeInvalidateRate())));
        result.put("점수가중치", groupBy(valid, r -> r.params.getWeightA() + "-" + r.params.getWeightB() + "-" + r.params.getWeightC() + "-" + r.params.getWeightD()));
        result.put("점수임계값", groupBy(valid, r -> String.valueOf(r.params.getBuyScoreThreshold())));
        result.put("손절기준", groupBy(valid, r -> percent(r.params.getStopLossRate())));
        result.put("시간청산기준일", groupBy(valid, r -> r.params.getTimeExitHoldingDays() + "일"));
        return result;
    }

    private static List<FactorImpact> groupBy(List<BacktestRunner.ParamGroupResult> all,
                                                java.util.function.Function<BacktestRunner.ParamGroupResult, String> keyFn) {
        Map<String, List<BacktestRunner.ParamGroupResult>> grouped = all.stream().collect(Collectors.groupingBy(keyFn));
        return grouped.entrySet().stream().map(e -> {
            List<BacktestRunner.ParamGroupResult> group = e.getValue();
            int totalTrades = group.stream().mapToInt(r -> r.totalTrades).sum();
            BigDecimal avgWinRate = avgWeighted(group, r -> r.avgWinRate, r -> r.totalTrades);
            BigDecimal avgProfitFactor = avg(group, r -> r.avgProfitFactor);
            BigDecimal avgSharpe = avg(group, r -> r.avgSharpe);
            return new FactorImpact(e.getKey(), totalTrades, group.size(), avgWinRate, avgProfitFactor, avgSharpe);
        }).sorted(Comparator.comparing(f -> f.value)).collect(Collectors.toList());
    }

    private static BigDecimal avg(List<BacktestRunner.ParamGroupResult> group,
                                   java.util.function.Function<BacktestRunner.ParamGroupResult, BigDecimal> fn) {
        if (group.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = group.stream().map(fn).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(group.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal avgWeighted(List<BacktestRunner.ParamGroupResult> group,
                                           java.util.function.Function<BacktestRunner.ParamGroupResult, BigDecimal> valueFn,
                                           java.util.function.Function<BacktestRunner.ParamGroupResult, Integer> weightFn) {
        int totalWeight = group.stream().mapToInt(weightFn::apply).sum();
        if (totalWeight == 0) return BigDecimal.ZERO;
        BigDecimal sum = group.stream()
                .map(r -> valueFn.apply(r).multiply(BigDecimal.valueOf(weightFn.apply(r))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP);
    }

    private static String formatKrw(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(100_000_000L)) + "억원";
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    @Getter
    public static class RowView {
        String label;
        int totalTrades;
        BigDecimal winRate;
        BigDecimal cumulativeReturn;
        BigDecimal maxDrawdown;
        BigDecimal profitFactor;
        BigDecimal sharpeRatio;
        BacktestParams params;

        static RowView from(BacktestRunner.ParamGroupResult r) {
            RowView v = new RowView();
            v.label = r.params.label();
            v.totalTrades = r.totalTrades;
            v.winRate = r.avgWinRate;
            v.cumulativeReturn = r.sumCumulativeReturn;
            v.maxDrawdown = r.worstMdd;
            v.profitFactor = r.avgProfitFactor;
            v.sharpeRatio = r.avgSharpe;
            v.params = r.params;
            return v;
        }
    }

    @Getter
    public static class FactorImpact {
        String value;
        int totalTrades;
        int comboCount;
        BigDecimal avgWinRate;
        BigDecimal avgProfitFactor;
        BigDecimal avgSharpe;

        FactorImpact(String value, int totalTrades, int comboCount, BigDecimal avgWinRate, BigDecimal avgProfitFactor, BigDecimal avgSharpe) {
            this.value = value;
            this.totalTrades = totalTrades;
            this.comboCount = comboCount;
            this.avgWinRate = avgWinRate;
            this.avgProfitFactor = avgProfitFactor;
            this.avgSharpe = avgSharpe;
        }
    }
}
