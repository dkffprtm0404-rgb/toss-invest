package com.tossinvest.tossinvestbackend.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PortfolioResult(String status, BigDecimal initialCapital, BigDecimal finalEquity, BigDecimal totalReturn,
        BigDecimal maxDrawdown, BigDecimal cash, List<EquityPoint> equity, List<Trade> trades, List<Selection> selections,
        List<Holding> holdings, List<Pending> pending, List<String> assumptions) {
    public record EquityPoint(LocalDate date, BigDecimal equity, BigDecimal cash) { }
    public record Trade(LocalDate date, LocalDate signalDate, String symbol, String side, long quantity, BigDecimal price,
                        BigDecimal fee, BigDecimal tax, String reason, BigDecimal cashAfter) { }
    public record Selection(LocalDate date, List<Ranked> ranked, List<Excluded> excluded) { }
    public record Ranked(String symbol, int rank, BigDecimal returnRate, BigDecimal close, BigDecimal sma, boolean selected) { }
    public record Excluded(String symbol, String reason) { }
    public record Holding(String symbol, long quantity, BigDecimal averageEntry, BigDecimal close, BigDecimal value) { }
    public record Pending(LocalDate signalDate, String symbol, String side, String reason) { }
}
