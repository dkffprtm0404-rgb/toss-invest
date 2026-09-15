package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.StrategyBar;
import com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.PeakBasis;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestResult.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator.*;

/** User rules use their own execution path, never the baseline score or trading-pause filters. */
@Component
public class UserStrategyBacktestEngine {
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final StrategyEvaluator evaluator;

    public UserStrategyBacktestEngine(StrategyEvaluator evaluator) { this.evaluator = evaluator; }

    public UserStrategyBacktestResult run(UserStrategyBacktestRequest request, List<CandleEntity> candles) {
        request.requireValid();
        List<StrategyBar> bars = validatedBars(request, candles);
        Evaluation evaluation = evaluator.prepare(request.strategy(), bars);
        int first = 0;
        while (first < bars.size() && date(bars.get(first).timestamp()).isBefore(request.startDate())) first++;
        int candleCount = bars.size() - first;
        List<Trade> trades = new ArrayList<>();
        Position position = null;
        Order pending = null;
        boolean entryWasReady = false;
        boolean nextOpen = request.executionMode() == NEXT_DAY_OPEN;
        for (int i = first; i < bars.size(); i++) {
            StrategyBar bar = bars.get(i);
            entryWasReady |= evaluation.entryReady(i, nextOpen);
            // The pending order was decided at an earlier close. No current close is read to fill it.
            if (pending != null) {
                Fill fill = fill(pending, bar, true);
                if (pending.buy()) {
                    validateAtrEntry(evaluation, i, fill);
                    position = new Position(i, fill);
                }
                else {
                    trades.add(position.close(i, fill));
                    position = null;
                }
                pending = null;
            }
            if (position != null) {
                boolean highPeak = request.strategy().risk() != null && request.strategy().risk().trailingStop() != null
                        && request.strategy().risk().trailingStop().peakBasis() == PeakBasis.HIGH;
                position.peak = position.peak.max(highPeak ? bar.high() : bar.close());
                Decision decision = evaluation.exit(i, new PositionContext(position.entry.price(), i - position.index, position.peak));
                if (decision.matched()) {
                    Order order = new Order(false, bar, decision);
                    if (request.executionMode() == NEXT_DAY_OPEN) pending = order;
                    else {
                        trades.add(position.close(i, fill(order, bar, false)));
                        position = null;
                    }
                }
                // Also prevents same-close re-entry and entry-bar close-mode exit.
                continue;
            }
            Decision decision = evaluation.entry(i, nextOpen);
            if (decision.matched()) {
                Order order = new Order(true, bar, decision);
                if (request.executionMode() == NEXT_DAY_OPEN) pending = order;
                else {
                    Fill entry = fill(order, bar, false);
                    validateAtrEntry(evaluation, i, entry);
                    position = new Position(i, entry);
                }
            }
        }
        OpenPosition open = null;
        if (position != null) {
            StrategyBar last = bars.get(bars.size() - 1);
            open = new OpenPosition(position.entry, marketTime(last.timestamp(), false), last.close(),
                    bars.size() - 1 - position.index, returnRate(last.close(), position.entry.price()));
        }
        PendingOrder unfilled = pending == null ? null : new PendingOrder(pending.buy() ? "BUY" : "SELL",
                marketTime(pending.signal().timestamp(), false), pending.signal().timestamp(), pending.decision().reason(),
                pending.decision().evidence(), "NO_NEXT_BAR");
        String status = candleCount == 0 ? "NO_DATA" : !entryWasReady ? "INSUFFICIENT_DATA"
                : trades.isEmpty() && open == null && unfilled == null ? "NO_TRADES" : "COMPLETED";
        return new UserStrategyBacktestResult(request, status, candleCount, first, evaluation.requiredWarmupBars(nextOpen),
                candleCount == 0 ? null : date(bars.get(first).timestamp()),
                candleCount == 0 ? null : date(bars.get(bars.size() - 1).timestamp()),
                Metrics.from(request.symbol(), trades), trades, open, unfilled, assumptions(request));
    }

    private void validateAtrEntry(Evaluation evaluation, int index, Fill entry) {
        try { evaluation.atrStopPrice(index, entry.price()); }
        catch (IllegalArgumentException ex) { throw new DataException(entry.executionBarTimestamp(), ex.getMessage()); }
    }

    private Fill fill(Order order, StrategyBar execution, boolean atOpen) {
        if (execution.volume().signum() == 0)
            throw new DataException(execution.timestamp(), "Cannot fill an order on a zero-volume bar.");
        return new Fill(marketTime(order.signal().timestamp(), false), marketTime(execution.timestamp(), atOpen),
                order.signal().timestamp(), execution.timestamp(), atOpen ? execution.open() : execution.close(),
                order.decision().reason(), order.decision().evidence());
    }

    private List<StrategyBar> validatedBars(UserStrategyBacktestRequest request, List<CandleEntity> candles) {
        List<StrategyBar> bars = new ArrayList<>();
        LocalDate previous = null;
        for (CandleEntity c : candles) {
            if (c == null || c.getTimestamp() == null) throw new DataException(null, "A candle timestamp is missing.");
            LocalDate day = date(c.getTimestamp());
            if (day.isAfter(request.endDate())) continue;
            if (!request.symbol().equals(c.getSymbol())) throw new DataException(c.getTimestamp(), "Candle symbol does not match the request.");
            if (previous != null && !day.isAfter(previous))
                throw new DataException(c.getTimestamp(), "Candles must contain one bar per date in ascending order.");
            if (!positive(c.getOpenPrice()) || !positive(c.getHighPrice()) || !positive(c.getLowPrice()) || !positive(c.getClosePrice()))
                throw new DataException(c.getTimestamp(), "OHLC prices must be positive.");
            if (c.getHighPrice().compareTo(c.getOpenPrice().max(c.getClosePrice())) < 0
                    || c.getLowPrice().compareTo(c.getOpenPrice().min(c.getClosePrice())) > 0)
                throw new DataException(c.getTimestamp(), "OHLC prices are inconsistent.");
            if (c.getVolume() == null || c.getVolume().signum() < 0)
                throw new DataException(c.getTimestamp(), "Volume must be non-negative.");
            bars.add(new StrategyBar(c.getTimestamp(), c.getOpenPrice(), c.getClosePrice(), c.getVolume(), c.getHighPrice(), c.getLowPrice()));
            previous = day;
        }
        return bars;
    }

    private boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    public static LocalDate date(long timestamp) { return Instant.ofEpochMilli(timestamp).atZone(MARKET_ZONE).toLocalDate(); }

    private static long marketTime(long barTimestamp, boolean atOpen) {
        return date(barTimestamp).atTime(atOpen ? LocalTime.of(9, 0) : LocalTime.of(15, 30))
                .atZone(MARKET_ZONE).toInstant().toEpochMilli();
    }

    private List<String> assumptions(UserStrategyBacktestRequest request) {
        List<String> assumptions = new ArrayList<>(List.of("DAILY_LONG_ONLY_SINGLE_POSITION", "NO_CAPITAL_QUANTITY_OR_COST_MODEL",
                "CLOSED_TRADE_SUM_NOT_COMPOUND_ACCOUNT_RETURN", "TRADE_DRAWDOWN_NOT_DAILY_EQUITY_DRAWDOWN",
                "TRADE_SHARPE_NOT_ANNUALIZED_ZERO_RISK_FREE", "OPEN_POSITION_EXCLUDED_FROM_CLOSED_METRICS",
                "HOLDING_BARS_NOT_CALENDAR_DAYS", "AVAILABLE_HISTORY_USED_FOR_INDICATOR_WARMUP",
                "KST_DAILY_TIMESTAMPS_ASSUMED_OPEN_09_00_CLOSE_15_30", "MISSING_MARKET_DAYS_NOT_INFERRED",
                "SIMPLE_RSI_FLAT_WINDOW_IS_100", "ZERO_VOLUME_BAR_CANNOT_FILL",
                request.executionMode() == SAME_DAY_CLOSE ? "SAME_CLOSE_FILL_IS_A_SIMULATION_ASSUMPTION" : "NEXT_AVAILABLE_BAR_OPEN_WITHIN_REQUESTED_PERIOD"));
        if (Integer.valueOf(2).equals(request.strategy().schemaVersion())) {
            assumptions.add("SIGNALS_AND_RISK_AT_DAILY_CLOSE_NOT_INTRADAY");
            assumptions.add("RANGE_EXCLUDES_CURRENT_BAR_CALENDAR_WEEKS_USE_KST");
            if (request.strategy().risk() != null && request.strategy().risk().atrStop() != null)
                assumptions.add("ATR_STOP_FROZEN_FROM_BAR_BEFORE_ENTRY");
        }
        return assumptions;
    }

    private record Order(boolean buy, StrategyBar signal, Decision decision) { }
    private static final class Position {
        private final int index;
        private final Fill entry;
        private BigDecimal peak;
        private Position(int index, Fill entry) { this.index = index; this.entry = entry; this.peak = entry.price(); }
        private Trade close(int exitIndex, Fill exit) {
            return new Trade(entry, exit, exitIndex - index, returnRate(exit.price(), entry.price()));
        }
    }

    public static class DataException extends IllegalArgumentException {
        private final Long timestamp;
        public DataException(Long timestamp, String message) { super(message); this.timestamp = timestamp; }
        public Long getTimestamp() { return timestamp; }
    }
}
