package com.tossinvest.tossinvestbackend.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine.MARKET_ZONE;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestResult.*;

/** Read-only projection of one immutable execution. No engine or current-market-data access. */
public record BacktestAnalysis(long runId, String status, Metrics metrics, List<PricePoint> prices,
                               List<CurvePoint> curve, List<NumberedTrade> trades, List<Group> months,
                               List<Group> exitReasons, List<Fact> facts) {
    public record PricePoint(long timestamp, BigDecimal close) { }
    public record CurvePoint(int tradeNumber, long timestamp, BigDecimal sumReturnRate, BigDecimal drawdown) { }
    public record NumberedTrade(int number, Trade trade) { }
    public record Group(String key, int closedTrades, BigDecimal winRate, BigDecimal sumReturnRate,
                        List<Integer> tradeNumbers) { }
    public record Fact(String id, BigDecimal value, String unit, String text, List<Integer> tradeNumbers) { }

    public static BacktestAnalysis from(SavedBacktestService.RunDetail run) {
        var snapshot = run.snapshot();
        var result = snapshot.result();
        if (result == null) return new BacktestAnalysis(run.id(), run.status(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var request = snapshot.execution();
        var prices = snapshot.data().candles().stream().filter(c -> {
            var date = Instant.ofEpochMilli(c.timestamp()).atZone(MARKET_ZONE).toLocalDate();
            return !date.isBefore(request.startDate()) && !date.isAfter(request.endDate());
        }).map(c -> new PricePoint(c.timestamp(), c.close())).toList();
        List<NumberedTrade> trades = new ArrayList<>();
        List<CurvePoint> curve = new ArrayList<>();
        Map<String, List<NumberedTrade>> byMonth = new LinkedHashMap<>(), byReason = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO, peak = BigDecimal.ZERO;
        if (!result.trades().isEmpty()) {
            curve.add(new CurvePoint(0, result.actualStartDate().atStartOfDay(MARKET_ZONE).toInstant().toEpochMilli(), sum, sum));
        }
        for (Trade trade : result.trades()) {
            var numbered = new NumberedTrade(trades.size() + 1, trade);
            trades.add(numbered);
            sum = sum.add(trade.returnRate());
            peak = peak.max(sum);
            curve.add(new CurvePoint(numbered.number(), trade.exit().executionTimestamp(), sum, sum.subtract(peak)));
            String month = YearMonth.from(Instant.ofEpochMilli(trade.exit().executionTimestamp()).atZone(MARKET_ZONE)).toString();
            byMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(numbered);
            byReason.computeIfAbsent(trade.exit().reason(), ignored -> new ArrayList<>()).add(numbered);
        }
        var months = groups(byMonth);
        var reasons = groups(byReason);
        List<Fact> facts = new ArrayList<>();
        var metrics = result.metrics();
        if (!trades.isEmpty()) {
            facts.add(new Fact("summary:return", metrics.sumTradeReturnRate(), "RATE",
                    "청산 거래 " + trades.size() + "건의 수익률 단순 합계는 " + percent(metrics.sumTradeReturnRate())
                            + "입니다. 비용과 투자 비중을 반영한 계좌 수익률이 아닙니다.", List.of()));
            facts.add(new Fact("summary:win-rate", metrics.winRate(), "RATE",
                    "청산 거래 승률은 " + percent(metrics.winRate()) + "입니다. 수익률이 양수인 거래를 승리로 계산합니다.", List.of()));
            facts.add(new Fact("summary:drawdown", metrics.tradeReturnMaxDrawdown(), "PERCENTAGE_POINTS",
                    "거래 수익률 누적 합계의 최대 낙폭은 " + percentageNumber(metrics.tradeReturnMaxDrawdown())
                            + "%p입니다. 미실현 손익을 반영한 일별 계좌 낙폭이 아닙니다.", List.of()));
            facts.add(new Fact("summary:sharpe", metrics.tradeSharpeRatio(), "NUMBER",
                    "거래 단위 샤프비율은 " + metrics.tradeSharpeRatio().toPlainString()
                            + "입니다. 거래 수익률 평균을 모집단 표준편차로 나누며 무위험수익률은 0, 연율화는 하지 않습니다."
                            + " 표준편차가 0인 경우 기존 엔진은 0을 반환합니다.", List.of()));
            for (Group group : months) facts.add(new Fact("month:" + group.key(), group.sumReturnRate(), "RATE",
                    group.key() + "에 청산한 " + group.closedTrades() + "건의 거래 수익률 합계는 " + percent(group.sumReturnRate())
                            + "입니다. 청산월 기준 집계이며 해당 월의 일별 계좌 수익률이 아닙니다.", group.tradeNumbers()));
            for (Group group : reasons) facts.add(new Fact("reason:" + group.key(), group.sumReturnRate(), "RATE",
                    reason(group.key()) + " 사유로 청산한 " + group.closedTrades() + "건의 거래 수익률 합계는 "
                            + percent(group.sumReturnRate()) + "입니다. 기록된 청산 사유에 따른 집계입니다.", group.tradeNumbers()));
            for (NumberedTrade item : trades) {
                Trade trade = item.trade();
                facts.add(new Fact("trade:" + item.number(), trade.returnRate(), "RATE",
                        "거래 #" + item.number() + "는 " + date(trade.entry().executionTimestamp()) + "에 " + trade.entry().price().toPlainString()
                                + "원으로 진입하고 " + date(trade.exit().executionTimestamp()) + "에 " + trade.exit().price().toPlainString()
                                + "원으로 청산했습니다. 청산 사유는 " + reason(trade.exit().reason()) + ", 수익률은 "
                                + percent(trade.returnRate()) + "입니다. 조건의 실제 관측값은 이 거래의 근거에서 확인할 수 있습니다.", List.of(item.number())));
            }
        }
        return new BacktestAnalysis(run.id(), run.status(), metrics, prices, List.copyOf(curve), List.copyOf(trades),
                months, reasons, List.copyOf(facts));
    }

    private static List<Group> groups(Map<String, List<NumberedTrade>> grouped) {
        return grouped.entrySet().stream().map(entry -> {
            var items = entry.getValue();
            long wins = items.stream().filter(t -> t.trade().returnRate().signum() > 0).count();
            var sum = items.stream().map(t -> t.trade().returnRate()).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Group(entry.getKey(), items.size(), BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(items.size()), 6,
                    RoundingMode.HALF_UP), sum, items.stream().map(NumberedTrade::number).toList());
        }).toList();
    }
    private static String date(long timestamp) { return Instant.ofEpochMilli(timestamp).atZone(MARKET_ZONE).toLocalDate().toString(); }
    private static String percentageNumber(BigDecimal value) { return value.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String percent(BigDecimal value) { return percentageNumber(value) + "%"; }
    private static String reason(String value) {
        return switch (value) {
            case "STOP_LOSS" -> "손절";
            case "ATR_STOP_LOSS" -> "ATR 손절";
            case "TAKE_PROFIT" -> "익절";
            case "TIME_EXIT" -> "최대 보유기간 초과";
            case "TRAILING_STOP" -> "트레일링 스탑";
            case "EXIT_CONDITIONS" -> "사용자 청산 조건 충족";
            default -> value;
        };
    }
}
