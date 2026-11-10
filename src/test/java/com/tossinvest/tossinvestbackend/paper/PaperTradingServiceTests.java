package com.tossinvest.tossinvestbackend.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.signal.SignalType;
import com.tossinvest.tossinvestbackend.signal.TradeSignal;
import com.tossinvest.tossinvestbackend.signal.TradeSignalService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PaperTradingServiceTests {
    private final PaperPositionRepository repo = mock(PaperPositionRepository.class);
    private final TradeSignalService signals = mock(TradeSignalService.class);
    private final MarketDataService market = mock(MarketDataService.class);
    private final PaperTradingService service = new PaperTradingService(repo, signals, market);

    @Test
    void exactSevenPercentCombinedLossStillBlocksNewBuys() {
        when(repo.findClosedOrderByExitDateDesc()).thenReturn(List.of(
                PaperPosition.builder().symbol("A").status("CLOSED").exitDate(LocalDate.now().minusDays(1))
                        .returnRate(new BigDecimal("-0.01")).build(),
                PaperPosition.builder().symbol("B").status("CLOSED").exitDate(LocalDate.now().minusDays(2))
                        .returnRate(new BigDecimal("-0.06")).build()));
        when(signals.evaluateForBuy("005930")).thenReturn(TradeSignal.builder()
                .symbol("005930").signalType(SignalType.BUY_CANDIDATE)
                .currentPrice(new BigDecimal("100000")).build());

        assertThat(service.runDaily(List.of("005930")).bought()).isEmpty();
    }

    @Test
    void lowHistoricalWinRateDoesNotBlockNewPaperBuys() {
        when(repo.findClosedOrderByExitDateDesc()).thenReturn(IntStream.range(0, 20)
                .mapToObj(i -> PaperPosition.builder().symbol("OLD").status("CLOSED")
                        .exitDate(LocalDate.now().minusDays(30))
                        .returnRate(new BigDecimal(i < 5 ? "0.01" : "-0.01")).build()).toList());
        when(signals.evaluateForBuy("005930")).thenReturn(TradeSignal.builder()
                .symbol("005930").signalType(SignalType.BUY_CANDIDATE)
                .currentPrice(new BigDecimal("100000")).build());

        var result = service.runDaily(List.of("005930"));

        assertThat(result.bought()).containsExactly("005930@100000");
        verify(repo).saveAll(argThat(positions -> {
            var position = positions.iterator().next();
            return position.getSymbol().equals("005930") && position.getStatus().equals("OPEN");
        }));
    }

    @Test
    void recentLossLimitStillBlocksNewBuys() {
        when(repo.findClosedOrderByExitDateDesc()).thenReturn(List.of(PaperPosition.builder()
                .symbol("OLD").status("CLOSED").exitDate(LocalDate.now().minusDays(1))
                .returnRate(new BigDecimal("-0.07")).build()));

        assertThat(service.runDaily(List.of("005930")).bought()).isEmpty();
        verifyNoInteractions(signals);
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void dailySellCountsObservedSessionsAcrossGapsInsteadOfCalendarDays() {
        var position=openPositionWithObservedDates(4,true);
        var actualSignals=new TradeSignalService(market);
        var actualService=new PaperTradingService(repo,actualSignals,market);

        var result=actualService.runDaily(List.of("005930"));

        assertThat(result.sold()).isEmpty();
        assertThat(result.held()).containsExactly("005930");
        assertThat(position.getStatus()).isEqualTo("OPEN");
        verify(market,times(1)).getCandles("005930","1d",60);
    }

    @Test
    void intradayMonitorUsesTheSameObservedSessionCount() {
        var position=openPositionWithObservedDates(4,true);
        var actualService=new PaperTradingService(repo,new TradeSignalService(market),market);

        assertThat(actualService.monitorOpenPositions()).isEmpty();

        assertThat(position.getStatus()).isEqualTo("OPEN");
        verify(market,times(1)).getCandles("005930","1d",60);
    }

    @Test
    void entryDayIsZeroAndFiveCompletedSessionsDoNotTriggerTimeExit() {
        var position=openPositionWithObservedDates(5,false);
        var actualService=new PaperTradingService(repo,new TradeSignalService(market),market);

        assertThat(actualService.runDaily(List.of("005930")).sold()).isEmpty();

        assertThat(position.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void sixDistinctCompletedSessionsStillTriggerTheExistingTimeExit() {
        var position=openPositionWithObservedDates(6,false);
        var actualService=new PaperTradingService(repo,new TradeSignalService(market),market);

        assertThat(actualService.runDaily(List.of("005930")).sold()).hasSize(1);

        assertThat(position.getStatus()).isEqualTo("CLOSED");
        assertThat(position.getExitReason()).isEqualTo("SELL_TREND_REVERSAL");
        verify(market,times(1)).getCandles("005930","1d",60);
    }

    @Test
    void aZeroVolumeDailyBarDoesNotAdvanceTheHeldSessionCount() {
        var position=openPositionWithObservedDates(5,false,true);
        var actualService=new PaperTradingService(repo,new TradeSignalService(market),market);

        assertThat(actualService.runDaily(List.of("005930")).sold()).isEmpty();

        assertThat(position.getStatus()).isEqualTo("OPEN");
    }

    private PaperPosition openPositionWithObservedDates(int completedSessions,boolean duplicateAndFuture) {
        return openPositionWithObservedDates(completedSessions,duplicateAndFuture,false);
    }

    private PaperPosition openPositionWithObservedDates(int completedSessions,boolean duplicateAndFuture,boolean zeroVolume) {
        LocalDate entry=LocalDate.of(2025,10,2);
        var position=PaperPosition.builder().symbol("005930").entryDate(entry)
                .entryPrice(new BigDecimal("100")).build();
        when(repo.findByStatus("OPEN")).thenReturn(List.of(position));
        when(repo.findClosedOrderByExitDateDesc()).thenReturn(List.of());
        var dates=new ArrayList<>(IntStream.rangeClosed(-20,0).mapToObj(entry::plusDays).toList());
        var observed=List.of(LocalDate.of(2025,10,10),LocalDate.of(2025,10,13),LocalDate.of(2025,10,14),
                LocalDate.of(2025,10,15),LocalDate.of(2025,10,16),LocalDate.of(2025,10,17));
        dates.addAll(observed.subList(0,completedSessions));
        if(duplicateAndFuture) {
            dates.add(observed.get(completedSessions-1));
            dates.add(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1));
        }
        LocalDate zeroVolumeDate=LocalDate.of(2025,10,20);
        if(zeroVolume) dates.add(zeroVolumeDate);
        dates.sort(Comparator.reverseOrder());
        var bars=dates.stream().map(day->Map.of("timestamp",day.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString(),
                "openPrice","100","highPrice","100","lowPrice","100","closePrice","100","volume",zeroVolume&&day.equals(zeroVolumeDate)?"0":"1000")).toList();
        var response=new ObjectMapper().convertValue(Map.of("result",Map.of("candles",bars)),CandleResponse.class);
        when(market.getCandles("005930","1d",60)).thenReturn(response);
        return position;
    }
}
