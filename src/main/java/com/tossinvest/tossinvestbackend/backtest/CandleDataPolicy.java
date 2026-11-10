package com.tossinvest.tossinvestbackend.backtest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Shared date and price rules for API collection and CSV imports. */
final class CandleDataPolicy {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int RETENTION_YEARS = 3;
    private static final LocalTime DAILY_CLOSE = LocalTime.of(20, 0);

    private CandleDataPolicy() { }

    static ZonedDateTime now() { return ZonedDateTime.now(KST); }
    static LocalDate oldestDate(ZonedDateTime now) { return now.toLocalDate().minusYears(RETENTION_YEARS); }
    static long startOfDay(LocalDate date) { return date.atStartOfDay(KST).toInstant().toEpochMilli(); }
    static LocalDate date(long timestamp) { return Instant.ofEpochMilli(timestamp).atZone(KST).toLocalDate(); }

    /** Conservative NXT close boundary; holidays are deliberately not inferred from weekdays. */
    static LocalDate latestCompletedWeekday(ZonedDateTime now) {
        LocalDate date = now.toLocalTime().isBefore(DAILY_CLOSE) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        while (weekend(date)) date = date.minusDays(1);
        return date;
    }

    static LocalDate firstWeekdayOnOrAfter(LocalDate date) {
        while (weekend(date)) date = date.plusDays(1);
        return date;
    }

    private static boolean weekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static boolean valid(CandleEntity candle) {
        if (candle.getSymbol() == null || !candle.getSymbol().matches("[A-Za-z0-9]{1,32}")
                || candle.getTimestamp() == null || candle.getTimestamp() < 0
                || !positive(candle.getOpenPrice()) || !positive(candle.getHighPrice())
                || !positive(candle.getLowPrice()) || !positive(candle.getClosePrice())
                || candle.getVolume() == null || candle.getVolume().signum() < 0) return false;
        return candle.getHighPrice().compareTo(candle.getOpenPrice().max(candle.getClosePrice())) >= 0
                && candle.getLowPrice().compareTo(candle.getOpenPrice().min(candle.getClosePrice())) <= 0
                && candle.getHighPrice().compareTo(candle.getLowPrice()) >= 0;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
