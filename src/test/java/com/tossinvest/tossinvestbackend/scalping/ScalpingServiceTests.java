package com.tossinvest.tossinvestbackend.scalping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScalpingServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 10, 0);
    private final MarketDataService market = mock(MarketDataService.class);
    private final ScalpingPositionRepository repo = mock(ScalpingPositionRepository.class);
    private final ScalpingService service = new ScalpingService(market, repo);
    private final List<ScalpingPosition> saved = new ArrayList<>();

    @BeforeEach
    void captureSavedPositions() {
        when(repo.saveAll(any())).thenAnswer(invocation -> {
            Iterable<ScalpingPosition> positions = invocation.getArgument(0);
            positions.forEach(saved::add);
            return saved;
        });
    }

    @Test
    void configuredUniverseBuysFromNewestCompletedCandleInDescendingResponse() throws Exception {
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(NOW.minusMinutes(1), "101", "130", 25));
        try (var ignored = at(NOW)) {
            assertThat(service.tick().bought()).containsExactly("005930@101[A_EMA추세]");
        }
        assertThat(saved).singleElement().satisfies(position -> {
            assertThat(position.getEntryPrice()).isEqualByComparingTo("101");
            assertThat(position.getStatus()).isEqualTo("OPEN");
        });
    }

    @Test
    void debugUsesSameLatestPriceAndVolumeThresholdAsTrading() throws Exception {
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(NOW.minusMinutes(1), "101", "130", 25));
        try (var ignored = at(NOW)) {
            var debug = (Map<?, ?>) new ScalpingController(service, repo).debugSignal("005930");
            assertThat(debug.get("currentPrice")).isEqualTo(new BigDecimal("101"));
            assertThat(debug.get("latestTimestamp")).isEqualTo("2026-10-08T09:59+09:00");
            assertThat(debug.get("volRatio")).isEqualTo("1.30");
            assertThat(debug.get("buySignal")).isEqualTo(true);
        }
    }

    @Test
    void volumeAverageIncludesTheImmediatelyPreviousCandle() throws Exception {
        var mapper = new ObjectMapper();
        var tree = mapper.valueToTree(candles(NOW.minusMinutes(1), "101", "180", 25));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/result/candles/1")).put("volume", "500");
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(mapper.treeToValue(tree, CandleResponse.class));
        try (var ignored = at(NOW)) {
            // 직전 5봉 평균=(500+100+100+100+100)/5=180, 현재 거래량도 180이다.
            var debug = service.debugSignal("005930");
            assertThat(debug.get("volRatio")).isEqualTo("1.00");
            assertThat(service.tick().bought()).isEmpty();
        }
    }

    @Test
    void unfinishedMinuteDoesNotCreateBuySignal() throws Exception {
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(NOW, "110", "1000", 25));
        try (var ignored = at(NOW)) {
            assertThat(service.tick().bought()).isEmpty();
        }
        assertThat(saved).isEmpty();
    }

    @Test
    void staleCandlesDoNotCreateTrades() throws Exception {
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(NOW.minusDays(1), "101", "130", 25));
        try (var ignored = at(NOW)) {
            assertThat(service.tick().bought()).isEmpty();
        }
        assertThat(saved).isEmpty();
    }

    @Test
    void openPositionOutsideUniverseStillStopsOutWithOnlyOneFreshCandle() throws Exception {
        var position = position("999999");
        when(repo.findByStatus("OPEN")).thenReturn(List.of(position));
        when(market.getCandles(eq("999999"), eq("1m"), anyInt()))
                .thenReturn(candles(NOW.minusMinutes(1), "99.4", "100", 1));
        try (var ignored = at(NOW)) {
            assertThat(service.tick().sold()).hasSize(1);
        }
        assertThat(position.getStatus()).isEqualTo("CLOSED");
        assertThat(position.getReturnRate()).isEqualByComparingTo("-0.006");
        assertThat(position.getExitReason()).isEqualTo("STOP_LOSS(-0.6%)");
    }

    @Test
    void closesAtExactly1520WithoutRequiringIndicatorWarmup() throws Exception {
        var closeTime = NOW.withHour(15).withMinute(20);
        var position = position("005930");
        when(repo.findByStatus("OPEN")).thenReturn(List.of(position));
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(closeTime.minusMinutes(1), "100", "100", 1));
        try (var ignored = at(closeTime)) {
            assertThat(service.tick().sold()).hasSize(1);
        }
        assertThat(position.getExitReason()).isEqualTo("FORCE_EXIT");
        assertThat(position.getExitTime()).isEqualTo(closeTime);
    }

    @Test
    void weekendManualScanDoesNotTrade() throws Exception {
        var saturday = NOW.plusDays(2);
        when(market.getCandles(eq("005930"), eq("1m"), anyInt()))
                .thenReturn(candles(saturday.minusMinutes(1), "101", "130", 25));
        try (var ignored = at(saturday)) {
            assertThat(service.tick().bought()).isEmpty();
        }
        verifyNoInteractions(market);
    }

    @Test
    void providerWaitCrossing1520ClosesPositionsInsteadOfOpeningNewOnes() throws Exception {
        var started = NOW.withHour(15).withMinute(19).withSecond(50);
        var completed = NOW.withHour(15).withMinute(20);
        var position = position("005930");
        when(repo.findByStatus("OPEN")).thenReturn(List.of(position));
        when(market.getCandles(anyString(), eq("1m"), anyInt()))
                .thenReturn(candles(NOW.withHour(15).withMinute(18), "101", "130", 25));
        try (var mocked = at(started)) {
            mocked.when(() -> LocalDateTime.now(ZoneId.of("Asia/Seoul"))).thenReturn(started, completed);
            var result = service.tick();
            assertThat(result.bought()).isEmpty();
            assertThat(result.sold()).hasSize(1);
        }
        assertThat(position.getExitReason()).isEqualTo("FORCE_EXIT");
        assertThat(position.getExitTime()).isEqualTo(completed);
    }

    @Test
    void overlappingManualAndScheduledScansCreateOnlyOnePosition() throws Exception {
        var response = candles(NOW.minusMinutes(1), "101", "130", 25);
        when(repo.findByStatus("OPEN")).thenAnswer(invocation -> List.copyOf(saved));
        when(market.getCandles(eq("005930"), eq("1m"), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(100);
            return response;
        });
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var scans = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                start.await();
                try (var ignored = at(NOW)) { return service.tick(); }
            })).toList();
            start.countDown();
            int buys = scans.get(0).get(5, TimeUnit.SECONDS).bought().size()
                    + scans.get(1).get(5, TimeUnit.SECONDS).bought().size();
            assertThat(buys).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(40)
    void stalledProviderDoesNotBlockTheNextScheduledScanIndefinitely() throws Exception {
        when(market.getCandles(eq("005930"), eq("1m"), anyInt())).thenAnswer(invocation -> {
            new CountDownLatch(1).await();
            return null;
        });
        try (var ignored = at(NOW)) {
            assertThat(service.tick().bought()).isEmpty();
        }
        assertThat(saved).isEmpty();
    }

    private ScalpingPosition position(String symbol) {
        return ScalpingPosition.builder().symbol(symbol).entryTime(NOW.minusMinutes(10))
                .entryPrice(new BigDecimal("100")).build();
    }

    private MockedStatic<LocalDateTime> at(LocalDateTime time) {
        var mocked = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        mocked.when(LocalDateTime::now).thenReturn(time);
        mocked.when(() -> LocalDateTime.now(ZoneId.of("Asia/Seoul"))).thenReturn(time);
        return mocked;
    }

    private CandleResponse candles(LocalDateTime latest, String price, String volume, int count) throws Exception {
        var bars = new ArrayList<Map<String, String>>();
        for (int i = count - 1; i >= 0; i--) {
            String close = i == 0 ? price : "100";
            bars.add(Map.of("timestamp", latest.minusMinutes(i).atOffset(java.time.ZoneOffset.ofHours(9)).toString(),
                    "openPrice", close, "highPrice", close, "lowPrice", close, "closePrice", close,
                    "volume", i == 0 ? volume : "100", "currency", "KRW"));
        }
        Collections.reverse(bars);
        var mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(Map.of("result", Map.of("candles", bars))), CandleResponse.class);
    }
}
