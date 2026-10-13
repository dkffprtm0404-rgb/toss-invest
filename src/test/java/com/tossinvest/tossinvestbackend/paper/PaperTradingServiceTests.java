package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.signal.SignalType;
import com.tossinvest.tossinvestbackend.signal.TradeSignal;
import com.tossinvest.tossinvestbackend.signal.TradeSignalService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
}
