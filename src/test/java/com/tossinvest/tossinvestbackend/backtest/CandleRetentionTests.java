package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
class CandleRetentionTests {
    private static final String SYMBOL = "005930";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    @Autowired CandleRepository repository;
    @TempDir Path directory;
    private MarketDataService market;
    private CandleCollectionService collection;
    private MockedStatic<Clock> clocks;

    @BeforeEach void setUp() {
        at("2026-11-05T12:00:00Z"); // Thursday 21:00 KST, after the daily candle is complete.
        market = mock(MarketDataService.class);
        collection = new CandleCollectionService(market, repository);
    }

    @AfterEach void releaseClock() { clocks.close(); }

    @Test void scheduledRefreshAlsoPrunesExpiredRowsForSymbolsOutsideTheTradingUniverse() {
        var expired=bar("2023-11-04"); expired.setSymbol("091990");
        var retained=bar("2026-11-03"); retained.setSymbol("091990");
        repository.saveAllAndFlush(List.of(expired,retained));
        var scheduler=new CandleCollectionScheduler(collection,new BacktestUniverse(repository));

        assertThat(scheduler.runManual()).isZero();
        assertThat(repository.findBySymbolOrderByTimestampAsc("091990"))
                .extracting(c->CandleDataPolicy.date(c.getTimestamp()).toString()).containsExactly("2026-11-03");
        verifyNoInteractions(market);
    }

    @Test void smallManualTargetPreservesLongHistoryAndPrunesOnlyBeforeThreeYearBoundary() throws Exception {
        var history = new ArrayList<CandleEntity>();
        history.add(bar("2023-11-04"));
        history.add(bar("2023-11-05")); // Inclusive calendar-year retention boundary, even on a weekend.
        history.add(bar("2024-01-02"));
        for (int i = 0; i < 220; i++) history.add(bar(LocalDate.of(2025, 1, 1).plusDays(i).toString()));
        history.add(bar("2026-11-03"));
        repository.saveAllAndFlush(history);
        when(market.getCandles(SYMBOL, "1d", 200)).thenReturn(response(bar("2026-11-04")));

        assertThat(collection.collectDailyCandles(SYMBOL, 2)).isEqualTo(1);

        assertThat(dates()).hasSize(224).contains("2023-11-05", "2024-01-02", "2026-11-03", "2026-11-04")
                .doesNotContain("2023-11-04");
    }

    @Test void afterCloseDoesNotTreatYesterdayAsFreshWhenTheRequestedCountIsAlreadyPresent() throws Exception {
        repository.saveAllAndFlush(List.of(bar("2026-11-03"), bar("2026-11-04")));
        when(market.getCandles(SYMBOL, "1d", 200)).thenReturn(response(bar("2026-11-05")));

        assertThat(collection.collectDailyCandles(SYMBOL, 2)).isEqualTo(1);

        assertThat(dates()).containsExactly("2026-11-03", "2026-11-04", "2026-11-05");
    }

    @Test void beforeCloseDiscardsTodaysIncompleteDailyCandle() throws Exception {
        at("2026-11-05T02:00:00Z"); // 11:00 KST.
        when(market.getCandles(SYMBOL, "1d", 200)).thenReturn(response(bar("2026-11-05"), bar("2026-11-04")));
        when(market.getCandlesWithDateBefore(eq(SYMBOL), eq("1d"), eq(200), anyString())).thenReturn(response());

        assertThat(collection.collectDailyCandles(SYMBOL, 2)).isEqualTo(1);

        assertThat(dates()).containsExactly("2026-11-04");
    }

    @Test void retainedFreshCacheDoesNotCallTheProviderBeforeClose() {
        at("2026-11-05T02:00:00Z");
        repository.saveAllAndFlush(List.of(bar("2024-01-02"), bar("2026-11-04")));

        assertThat(collection.collectDailyCandles(SYMBOL, 2)).isZero();

        assertThat(dates()).containsExactly("2024-01-02", "2026-11-04");
        verifyNoInteractions(market);
    }

    @Test void repeatedHistoricalPageTerminatesWithoutFifteenIdenticalRequests() throws Exception {
        when(market.getCandles(SYMBOL, "1d", 200)).thenReturn(response(bar("2026-11-05")));
        when(market.getCandlesWithDateBefore(eq(SYMBOL), eq("1d"), eq(200), anyString()))
                .thenReturn(response(bar("2026-11-05")));

        assertThat(collection.collectDailyCandles(SYMBOL, 650)).isEqualTo(1);

        assertThat(dates()).containsExactly("2026-11-05");
        verify(market, times(1)).getCandlesWithDateBefore(eq(SYMBOL), eq("1d"), eq(200), anyString());
    }

    @Test void unsupportedHistoricalPaginationKeepsTheSuccessfulLatestPage() throws Exception {
        repository.saveAndFlush(bar("2024-01-02"));
        when(market.getCandles(SYMBOL, "1d", 200)).thenReturn(response(bar("2026-11-05"), bar("2026-11-04")));
        when(market.getCandlesWithDateBefore(eq(SYMBOL), eq("1d"), eq(200), anyString()))
                .thenThrow(WebClientResponseException.create(400, "Bad Request", null, new byte[0], StandardCharsets.UTF_8));

        assertThatCode(() -> assertThat(collection.collectDailyCandles(SYMBOL, 650)).isEqualTo(2))
                .doesNotThrowAnyException();

        assertThat(dates()).containsExactly("2024-01-02", "2026-11-04", "2026-11-05");
    }

    @Test void historicalPagesAdvanceFromTheirActualOldestDateAndKeepEveryRetainedBar() throws Exception {
        when(market.getCandles(SYMBOL, "1d", 200))
                .thenReturn(response(bar("2026-11-05"), bar("2026-08-03")));
        when(market.getCandlesWithDateBefore(SYMBOL, "1d", 200, "2026-08-03T00:00:00+09:00"))
                .thenReturn(response(bar("2025-01-02"), bar("2023-11-05"), bar("2023-11-04")));

        assertThat(collection.collectDailyCandles(SYMBOL, 650)).isEqualTo(4);

        assertThat(dates()).containsExactly("2023-11-05", "2025-01-02", "2026-08-03", "2026-11-05");
    }

    @Test void csvSkipsMalformedOhlcvDuplicatesAndExpiredRowsWithoutLosingValidRows() throws Exception {
        var csv = directory.resolve("candles.csv");
        Files.writeString(csv, "symbol,timestamp,open_price,high_price,low_price,close_price,volume\n"
                + row("2023-11-05", "100,110,90,105,1000")
                + row("2023-11-04", "100,110,90,105,1000")
                + row("2026-11-04", "100,101,90,105,1000") // high below close
                + row("2026-11-03", "100,110,90,105,-1")
                + row("2026-11-02", ",110,90,105,1000")
                + SYMBOL + ",not-a-timestamp,100,110,90,105,1000\n"
                + row("2023-11-05", "100,110,90,105,1000"));

        var result = new CandleCsvImportService(repository).importFromCsv(csv.toString());

        assertThat(result).isEqualTo(new CandleCsvImportService.ImportResult(7, 1, 6));
        assertThat(dates()).containsExactly("2023-11-05");
        assertThat(repository.findBySymbolOrderByTimestampAsc(SYMBOL).get(0).getClosePrice())
                .isEqualByComparingTo("105");
    }

    @Test void leapDayRetentionIncludesFebruaryTwentyEighthAndPrunesExistingExpiredRows() throws Exception {
        at("2028-02-29T12:00:00Z");
        repository.saveAllAndFlush(List.of(bar("2025-02-27"), bar("2025-02-28")));
        var csv = directory.resolve("leap.csv");
        Files.writeString(csv, "symbol,timestamp,open_price,high_price,low_price,close_price,volume\n"
                + row("2028-02-29", "100,110,90,105,1000"));

        var result = new CandleCsvImportService(repository).importFromCsv(csv.toString());

        assertThat(result).isEqualTo(new CandleCsvImportService.ImportResult(1, 1, 0));
        assertThat(dates()).containsExactly("2025-02-28", "2028-02-29");
    }

    private void at(String instant) {
        if (clocks != null) clocks.close();
        Clock fixed = Clock.fixed(Instant.parse(instant), KST);
        clocks = mockStatic(Clock.class, CALLS_REAL_METHODS);
        clocks.when(() -> Clock.system(KST)).thenReturn(fixed);
        clocks.when(Clock::systemDefaultZone).thenReturn(fixed);
    }

    private List<String> dates() {
        return repository.findBySymbolOrderByTimestampAsc(SYMBOL).stream()
                .map(c -> Instant.ofEpochMilli(c.getTimestamp()).atZone(KST).toLocalDate().toString()).toList();
    }

    private static CandleEntity bar(String date) {
        return new CandleEntity(SYMBOL, LocalDate.parse(date).atStartOfDay(KST).toInstant().toEpochMilli(),
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), new BigDecimal("1000"));
    }

    private static String row(String date, String ohlcv) {
        return SYMBOL + "," + LocalDate.parse(date).atStartOfDay(KST).toInstant().toEpochMilli() + "," + ohlcv + "\n";
    }

    private static CandleResponse response(CandleEntity... bars) throws Exception {
        var mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        var values = body.putObject("result").putArray("candles");
        for (var bar : bars) {
            values.addObject().put("timestamp", Instant.ofEpochMilli(bar.getTimestamp()).atZone(KST).toOffsetDateTime().toString())
                    .put("currency", "KRW").put("openPrice", bar.getOpenPrice().toPlainString())
                    .put("highPrice", bar.getHighPrice().toPlainString()).put("lowPrice", bar.getLowPrice().toPlainString())
                    .put("closePrice", bar.getClosePrice().toPlainString()).put("volume", bar.getVolume().toPlainString());
        }
        return mapper.treeToValue(body, CandleResponse.class);
    }
}
