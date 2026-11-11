package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/** Current cache inspection only; never an execution result or a guarantee of trading-day coverage. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataReadiness(String status, String symbol, LocalDate startDate, LocalDate endDate,
                            int totalCandleCount, LocalDate availableStartDate, LocalDate availableEndDate,
                            Integer candleCount, LocalDate actualStartDate, LocalDate actualEndDate,
                            Integer warmupBars, Integer requiredWarmupBars, boolean calendarBasedWarmup,
                            LocalDate firstReadyDate, Integer readyCandleCount, LocalDate invalidDataDate,
                            List<String> warnings) {
    public DataReadiness { warnings = List.copyOf(warnings); }
}
