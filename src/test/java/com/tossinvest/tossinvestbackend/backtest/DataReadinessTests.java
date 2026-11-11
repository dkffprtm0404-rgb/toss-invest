package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngineTests.candles;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngineTests.date;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.NEXT_DAY_OPEN;
import static org.assertj.core.api.Assertions.assertThat;

class DataReadinessTests {
    private final UserStrategyBacktestEngine engine = new UserStrategyBacktestEngine(new StrategyEvaluator());

    @Test void separatesWholeCacheFromRequestedWindowAndIndicatorHistory() throws Exception {
        var result = engine.readiness(request(1, 2, 3), candles(100, 100, 100, 100, 999, 999));
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.totalCandleCount()).isEqualTo(6);
        assertThat(result.availableStartDate()).isEqualTo(date(0));
        assertThat(result.availableEndDate()).isEqualTo(date(5));
        assertThat(result.candleCount()).isEqualTo(2);
        assertThat(result.warmupBars()).isEqualTo(2);
        assertThat(result.requiredWarmupBars()).isEqualTo(1);
        assertThat(result.actualStartDate()).isEqualTo(date(2));
        assertThat(result.actualEndDate()).isEqualTo(date(3));
        assertThat(result.firstReadyDate()).isEqualTo(date(2));
        assertThat(result.readyCandleCount()).isEqualTo(2);
    }

    @Test void reportsEmptyCacheWithoutInventingDates() throws Exception {
        var result = engine.readiness(request(1, 0, 2), List.of());
        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.totalCandleCount()).isZero();
        assertThat(result.candleCount()).isZero();
        assertThat(result.availableStartDate()).isNull();
        assertThat(result.availableEndDate()).isNull();
        assertThat(result.firstReadyDate()).isNull();
    }

    @Test void existingHistoryOutsideRequestIsStillNoData() throws Exception {
        var result = engine.readiness(request(1, 5, 6), candles(100, 100, 100));
        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.totalCandleCount()).isEqualTo(3);
        assertThat(result.warmupBars()).isEqualTo(3);
        assertThat(result.candleCount()).isZero();
        assertThat(result.actualStartDate()).isNull();
        assertThat(result.actualEndDate()).isNull();
    }

    @Test void missingIndicatorHistoryIsNotNoDataOrNoTrades() throws Exception {
        var result = engine.readiness(request(5, 0, 2), candles(100, 100, 100));
        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.candleCount()).isEqualTo(3);
        assertThat(result.warmupBars()).isZero();
        assertThat(result.requiredWarmupBars()).isEqualTo(5);
        assertThat(result.readyCandleCount()).isZero();
        assertThat(result.firstReadyDate()).isNull();
    }

    @Test void identifiesTheFirstReadyDayWhenOnlyPartOfTheWindowIsReady() throws Exception {
        var result = engine.readiness(request(2, 0, 3), candles(100, 100, 100, 100));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.candleCount()).isEqualTo(4);
        assertThat(result.readyCandleCount()).isEqualTo(2);
        assertThat(result.firstReadyDate()).isEqualTo(date(2));
    }

    @Test void orEntryCanBeReadyWhileAnotherBranchLacksHistory() throws Exception {
        var result = engine.readiness(group("OR", 2, 3), candles(100, 100, 100, 100));
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.requiredWarmupBars()).isEqualTo(2);
        assertThat(result.readyCandleCount()).isEqualTo(2);
        assertThat(result.warnings()).contains("ENTRY_READINESS_ONLY");
    }

    @Test void andEntryRequiresBothBranchesToBeReady() throws Exception {
        var result = engine.readiness(group("AND", 2, 3), candles(100, 100, 100, 100));
        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.requiredWarmupBars()).isEqualTo(4);
    }

    @Test void calendarBasedRequirementDoesNotInventAFixedMissingBarCount() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":2,"entry":{"conditions":[{"type":"RANGE_RATIO","period":52,
                "periodUnit":"CALENDAR_WEEKS","priceField":"HIGH","multiplier":0.75,"comparison":"GTE"}]}}
                """, StrategyDefinition.class);
        var request = new UserStrategyBacktestRequest(strategy, "005930", date(0), date(2), SAME_DAY_CLOSE);
        var result = engine.readiness(request, candles(100, 100, 100));
        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.calendarBasedWarmup()).isTrue();
        assertThat(result.requiredWarmupBars()).isNull();
        assertThat(result.warnings()).contains("CALENDAR_WARMUP");
    }

    @Test void calendarWeekRangeRatioBecomesReadyOnTheFirstFullCalendarBoundary() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":2,"entry":{"conditions":[{"type":"RANGE_RATIO","period":52,
                "periodUnit":"CALENDAR_WEEKS","priceField":"HIGH","multiplier":0.75,"comparison":"GTE"}]}}
                """, StrategyDefinition.class);
        var data = candles(80, 100, 150, 140);
        data.get(1).setTimestamp(UserStrategyBacktestEngineTests.time(363, 0, 0));
        data.get(2).setTimestamp(UserStrategyBacktestEngineTests.time(364, 0, 0));
        data.get(3).setTimestamp(UserStrategyBacktestEngineTests.time(365, 0, 0));
        data.forEach(c -> c.setHighPrice(c.getClosePrice()));
        data.get(0).setHighPrice(BigDecimal.valueOf(200));
        data.get(2).setHighPrice(BigDecimal.valueOf(999));
        var beforeRequest = new UserStrategyBacktestRequest(strategy, "005930", date(363), date(363), SAME_DAY_CLOSE);
        var boundaryRequest = new UserStrategyBacktestRequest(strategy, "005930", date(364), date(364), SAME_DAY_CLOSE);
        var windowRequest = new UserStrategyBacktestRequest(strategy, "005930", date(363), date(365), SAME_DAY_CLOSE);

        // 52주 전 날짜는 12/30에는 2024-12-31, 12/31에는 최초 이력일인 2025-01-01이다.
        var before = engine.readiness(beforeRequest, data);
        assertThat(before.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(before.candleCount()).isEqualTo(1);
        assertThat(before.readyCandleCount()).isZero();
        assertThat(before.firstReadyDate()).isNull();
        assertThat(before.calendarBasedWarmup()).isTrue();
        assertThat(before.requiredWarmupBars()).isNull();

        var boundary = engine.readiness(boundaryRequest, data);
        assertThat(boundary.status()).isEqualTo("READY");
        assertThat(boundary.candleCount()).isEqualTo(1);
        assertThat(boundary.warmupBars()).isEqualTo(2);
        assertThat(boundary.readyCandleCount()).isEqualTo(1);
        assertThat(boundary.firstReadyDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(boundary.calendarBasedWarmup()).isTrue();
        assertThat(boundary.requiredWarmupBars()).isNull();

        var window = engine.readiness(windowRequest, data);
        assertThat(window.status()).isEqualTo("PARTIAL");
        assertThat(window.candleCount()).isEqualTo(3);
        assertThat(window.warmupBars()).isEqualTo(1);
        assertThat(window.readyCandleCount()).isEqualTo(2);
        assertThat(window.firstReadyDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        // 경계일의 고가 200은 포함하고 당일 고가 999는 제외하므로 기준값은 200 * 0.75 = 150이다.
        var evidence = engine.run(boundaryRequest, data).openPosition().entry().evidence().get(0);
        assertThat(evidence.type()).isEqualTo("RANGE_RATIO");
        assertThat(evidence.actualValue()).isEqualByComparingTo("150");
        assertThat(evidence.referenceValue()).isEqualByComparingTo("150");
    }

    @Test void monthEndTrendReadinessUsesTheLastReadyAverageAtTheCalendarCutoff() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":2,"entry":{"conditions":[{"type":"MA_TREND","averageType":"SMA",
                "period":2,"lookbackMonths":1,"direction":"UP"}]}}
                """, StrategyDefinition.class);
        var data = candles(10, 20, 30, 40, 50, 60);
        var days = List.of("2024-02-28", "2024-02-29", "2024-03-28", "2024-03-29", "2024-03-30", "2024-03-31");
        for (int i = 0; i < days.size(); i++) {
            data.get(i).setTimestamp(LocalDate.parse(days.get(i)).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                    .toInstant().toEpochMilli());
        }
        var beforeRequest = new UserStrategyBacktestRequest(strategy, "005930",
                LocalDate.of(2024, 3, 28), LocalDate.of(2024, 3, 28), SAME_DAY_CLOSE);
        var windowRequest = new UserStrategyBacktestRequest(strategy, "005930",
                LocalDate.of(2024, 3, 28), LocalDate.of(2024, 3, 31), SAME_DAY_CLOSE);
        var monthEndRequest = new UserStrategyBacktestRequest(strategy, "005930",
                LocalDate.of(2024, 3, 31), LocalDate.of(2024, 3, 31), SAME_DAY_CLOSE);

        // 3/28의 한 달 전인 2/28에는 SMA2가 없다. 3/29부터 2/29의 SMA2 = (10 + 20) / 2 = 15를 쓴다.
        var before = engine.readiness(beforeRequest, data);
        assertThat(before.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(before.readyCandleCount()).isZero();
        assertThat(before.firstReadyDate()).isNull();

        var window = engine.readiness(windowRequest, data);
        assertThat(window.status()).isEqualTo("PARTIAL");
        assertThat(window.candleCount()).isEqualTo(4);
        assertThat(window.warmupBars()).isEqualTo(2);
        assertThat(window.readyCandleCount()).isEqualTo(3);
        assertThat(window.firstReadyDate()).isEqualTo(LocalDate.of(2024, 3, 29));
        assertThat(window.calendarBasedWarmup()).isTrue();
        assertThat(window.requiredWarmupBars()).isNull();
        var firstEvidence = engine.run(windowRequest, data).openPosition().entry().evidence().get(0);
        assertThat(firstEvidence.type()).isEqualTo("MA_TREND");
        assertThat(firstEvidence.actualValue()).isEqualByComparingTo("35");
        assertThat(firstEvidence.referenceValue()).isEqualByComparingTo("15");

        // 3/31 - 1개월은 윤일인 2/29로 보정된다. 당일 SMA2 = (50 + 60) / 2 = 55이다.
        var monthEnd = engine.readiness(monthEndRequest, data);
        assertThat(monthEnd.status()).isEqualTo("READY");
        assertThat(monthEnd.candleCount()).isEqualTo(1);
        assertThat(monthEnd.warmupBars()).isEqualTo(5);
        assertThat(monthEnd.readyCandleCount()).isEqualTo(1);
        assertThat(monthEnd.firstReadyDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(monthEnd.calendarBasedWarmup()).isTrue();
        assertThat(monthEnd.requiredWarmupBars()).isNull();
        var monthEndEvidence = engine.run(monthEndRequest, data).openPosition().entry().evidence().get(0);
        assertThat(monthEndEvidence.actualValue()).isEqualByComparingTo("55");
        assertThat(monthEndEvidence.referenceValue()).isEqualByComparingTo("15");
    }

    @Test void invalidPricesAreUnknownReadinessRatherThanZeroAvailableBars() throws Exception {
        var data = candles(100, 100, 100);
        data.get(1).setOpenPrice(BigDecimal.ZERO);
        var result = engine.readiness(request(1, 0, 2), data);
        assertThat(result.status()).isEqualTo("INVALID_DATA");
        assertThat(result.totalCandleCount()).isEqualTo(3);
        assertThat(result.invalidDataDate()).isEqualTo(date(1));
        assertThat(result.candleCount()).isNull();
        assertThat(result.warmupBars()).isNull();
        assertThat(result.readyCandleCount()).isNull();
    }

    @Test void futureInvalidPricesCannotAffectTheRequestedWindow() throws Exception {
        var data = candles(100, 100, 100, 100);
        data.get(3).setOpenPrice(BigDecimal.ZERO);
        var result = engine.readiness(request(1, 1, 2), data);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candleCount()).isEqualTo(2);
        assertThat(result.readyCandleCount()).isEqualTo(2);
    }

    @Test void requestOutsideStoredBoundariesIsPartialWithoutGuessingTradingDays() throws Exception {
        var result = engine.readiness(request(1, 1, 5), candles(100, 100, 100));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.candleCount()).isEqualTo(2);
        assertThat(result.readyCandleCount()).isEqualTo(2);
        assertThat(result.warnings()).contains("END_OUTSIDE_CACHE", "TRADING_CALENDAR_NOT_CHECKED");
    }

    @Test void gapsAreNotFilledWithInventedDailyBars() throws Exception {
        var data = candles(100, 100, 100, 100);
        data.get(2).setTimestamp(UserStrategyBacktestEngineTests.time(5, 0, 0));
        data.get(3).setTimestamp(UserStrategyBacktestEngineTests.time(6, 0, 0));
        var result = engine.readiness(request(1, 1, 6), data);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candleCount()).isEqualTo(3);
        assertThat(result.readyCandleCount()).isEqualTo(3);
        assertThat(result.warnings()).contains("TRADING_CALENDAR_NOT_CHECKED");
    }

    @Test void atrReadinessUsesTheExecutionModeWithoutPromisingANextOpenFill() throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":2,"entry":{"conditions":[{"type":"VOLUME","period":1,
                "multiplier":1,"comparison":"GTE"}]},"risk":{"atrStop":{"method":"SIMPLE","period":2,"multiplier":1}}}
                """, StrategyDefinition.class);
        var data = candles(100, 100, 100, 100);
        data.forEach(c -> { c.setHighPrice(BigDecimal.valueOf(102)); c.setLowPrice(BigDecimal.valueOf(98)); });
        var close = new UserStrategyBacktestRequest(strategy, "005930", date(2), date(2), SAME_DAY_CLOSE);
        var next = new UserStrategyBacktestRequest(strategy, "005930", date(2), date(2), NEXT_DAY_OPEN);
        var closeReadiness = engine.readiness(close, data);
        var nextReadiness = engine.readiness(next, data);
        assertThat(closeReadiness.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(closeReadiness.requiredWarmupBars()).isEqualTo(3);
        assertThat(nextReadiness.status()).isEqualTo("READY");
        assertThat(nextReadiness.requiredWarmupBars()).isEqualTo(2);
        assertThat(nextReadiness.readyCandleCount()).isEqualTo(1);
        assertThat(engine.run(next, data).pendingOrder().status()).isEqualTo("NO_NEXT_BAR");
    }

    @Test void midnightBoundaryUsesKoreanDatesInsteadOfUtcDates() throws Exception {
        var data = candles(100, 100);
        data.get(0).setTimestamp(java.time.Instant.parse("2025-01-01T14:30:00Z").toEpochMilli());
        data.get(1).setTimestamp(java.time.Instant.parse("2025-01-01T15:00:00Z").toEpochMilli());
        var result = engine.readiness(request(1, 1, 1), data);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.availableStartDate()).isEqualTo(java.time.LocalDate.of(2025, 1, 1));
        assertThat(result.actualStartDate()).isEqualTo(java.time.LocalDate.of(2025, 1, 2));
        assertThat(result.candleCount()).isEqualTo(1);
        assertThat(result.warmupBars()).isEqualTo(1);
    }

    private UserStrategyBacktestRequest request(int period, int start, int end) throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":1,"entry":{"conditions":[{"type":"VOLUME","period":%d,
                "multiplier":1,"comparison":"GTE"}]}}
                """.formatted(period), StrategyDefinition.class);
        return new UserStrategyBacktestRequest(strategy, "005930", date(start), date(end), SAME_DAY_CLOSE);
    }

    private UserStrategyBacktestRequest group(String operator, int start, int end) throws Exception {
        var strategy = new ObjectMapper().readValue("""
                {"schemaVersion":2,"entry":{"operator":"%s","conditions":[
                {"type":"VOLUME","period":2,"multiplier":1,"comparison":"GTE"},
                {"type":"PRICE_MA","averageType":"SMA","period":5,"comparison":"GTE"}]}}
                """.formatted(operator), StrategyDefinition.class);
        return new UserStrategyBacktestRequest(strategy, "005930", date(start), date(end), SAME_DAY_CLOSE);
    }
}
