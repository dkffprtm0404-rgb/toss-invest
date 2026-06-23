package com.tossinvest.tossinvestbackend.backtest;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 단일 종목, 단일 파라미터 조합에 대한 백테스트 결과 집계.
 * trading-rules.md 6장에서 요구한 지표(승률, MDD, 손익비, 샤프비율)를 계산한다.
 */
@Getter
public class BacktestResult {

    private String symbol;
    private BacktestParams params;
    private int totalTrades;
    private BigDecimal winRate;
    private BigDecimal avgReturnRate;
    private BigDecimal cumulativeReturn; // 단순 합산 누적수익률 (복리 아님, 1회 매수비중 10% 가정 단순화)
    private BigDecimal maxDrawdown; // MDD, 음수
    private BigDecimal profitFactor; // 손익비 = 총이익 / |총손실|
    private BigDecimal sharpeRatio; // 거래 단위 샤프 비율 근사치 (평균/표준편차, 무위험수익률 0 가정)
    private double avgHoldingDays;
    private List<BacktestTrade> trades;

    public static BacktestResult from(String symbol, BacktestParams params, List<BacktestTrade> trades) {
        BacktestResult r = new BacktestResult();
        r.symbol = symbol;
        r.params = params;
        r.trades = trades;
        r.totalTrades = trades.size();

        if (trades.isEmpty()) {
            r.winRate = BigDecimal.ZERO;
            r.avgReturnRate = BigDecimal.ZERO;
            r.cumulativeReturn = BigDecimal.ZERO;
            r.maxDrawdown = BigDecimal.ZERO;
            r.profitFactor = BigDecimal.ZERO;
            r.sharpeRatio = BigDecimal.ZERO;
            r.avgHoldingDays = 0;
            return r;
        }

        long wins = trades.stream().filter(t -> t.returnRate.signum() > 0).count();
        r.winRate = BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP);

        BigDecimal sumReturn = trades.stream().map(t -> t.returnRate).reduce(BigDecimal.ZERO, BigDecimal::add);
        r.avgReturnRate = sumReturn.divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP);
        r.cumulativeReturn = sumReturn;

        BigDecimal totalProfit = trades.stream().map(t -> t.returnRate).filter(rr -> rr.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = trades.stream().map(t -> t.returnRate).filter(rr -> rr.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        // 손실이 없는 경우(완전 우연) profitFactor=999로 치환하면 집계 평균이 튀므로 3.0으로 cap
        r.profitFactor = totalLoss.signum() == 0
                ? (totalProfit.signum() > 0 ? BigDecimal.valueOf(3.0) : BigDecimal.ZERO)
                : totalProfit.divide(totalLoss, 4, RoundingMode.HALF_UP);

        // MDD: 거래 순서대로 누적수익률 곡선을 그렸을 때의 최대 낙폭
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BacktestTrade t : trades) {
            cumulative = cumulative.add(t.returnRate);
            peak = peak.max(cumulative);
            BigDecimal drawdown = cumulative.subtract(peak);
            maxDd = maxDd.min(drawdown);
        }
        r.maxDrawdown = maxDd;

        // 샤프비율 근사: 거래별 수익률의 평균/표준편차 (무위험수익률 0, 연율화 생략 - 거래단위 비교용)
        double mean = r.avgReturnRate.doubleValue();
        double variance = trades.stream()
                .mapToDouble(t -> Math.pow(t.returnRate.doubleValue() - mean, 2))
                .sum() / trades.size();
        double stdDev = Math.sqrt(variance);
        r.sharpeRatio = stdDev == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(mean / stdDev).setScale(4, RoundingMode.HALF_UP);

        r.avgHoldingDays = trades.stream().mapToInt(t -> t.holdingDays).average().orElse(0);

        return r;
    }
}
