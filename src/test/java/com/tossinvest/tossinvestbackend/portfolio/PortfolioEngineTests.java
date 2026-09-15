package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.CandleEntity;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static org.assertj.core.api.Assertions.*;

class PortfolioEngineTests {
    final PortfolioEngine engine = new PortfolioEngine();
    final LocalDate start = LocalDate.of(2025, 7, 7);
    final List<LocalDate> calendar = LocalDate.of(2024, 12, 1).datesUntil(LocalDate.of(2025, 7, 26))
            .filter(d -> d.getDayOfWeek().getValue() <= 5).toList();

    @Test void sixCalendarMonthsReturnAndTiesUseCodeAndCashSlots() {
        List<CandleEntity> bars = bars("B", "A");
        PortfolioResult result = engine.run(strategy(2, SelectionOrder.FILTER_THEN_RANK, null), request(start, false, 2), bars);
        assertThat(result.selections()).hasSize(1);
        assertThat(result.selections().get(0).ranked()).extracting(PortfolioResult.Ranked::symbol).containsExactly("A", "B");
        assertThat(result.selections().get(0).ranked().get(0).returnRate()).isEqualByComparingTo("0.2");
        assertThat(result.trades()).extracting(PortfolioResult.Trade::quantity).containsExactly(4L, 4L);
        assertThat(result.cash()).isEqualByComparingTo("40");
        assertThat(result.finalEquity()).isEqualByComparingTo("1000");
    }

    @Test void costsRoundSharesDownAndNeverBorrow() {
        PortfolioRequest base = request(start, false, 1);
        PortfolioRequest costs = new PortfolioRequest(1, start, start, SAME_DAY_CLOSE, bd("1000"), bd("0.01"), bd("0.02"), bd("0.1"), base.universe());
        PortfolioResult result = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), costs, bars("A"));
        assertThat(result.trades().get(0).quantity()).isEqualTo(7);
        assertThat(result.trades().get(0).price()).isEqualByComparingTo("132");
        assertThat(result.trades().get(0).fee()).isEqualByComparingTo("9.24");
        assertThat(result.cash()).isEqualByComparingTo("66.76");
        assertThat(result.finalEquity()).isEqualByComparingTo("906.76");
    }

    @Test void nextOpenUsesHolidayCalendarAndDoesNotReadFutureClose() {
        LocalDate fill = start.plusDays(2);
        PortfolioRequest base = request(fill, true, 1);
        List<LocalDate> holidays = calendar.stream().filter(d -> !d.equals(start.plusDays(1))).toList();
        PortfolioRequest req = new PortfolioRequest(1, start, fill, NEXT_DAY_OPEN, bd("1000"), bd("0"), bd("0"), bd("0"),
                new PortfolioRequest.Universe("historical-test", holidays, base.universe().members()));
        List<CandleEntity> bars = bars("A");
        set(bars, "A", fill, "200", "1000");
        PortfolioResult result = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), req, bars);
        assertThat(result.trades().get(0).date()).isEqualTo(fill);
        assertThat(result.trades().get(0).signalDate()).isEqualTo(start);
        assertThat(result.trades().get(0).quantity()).isEqualTo(5);
        assertThat(result.finalEquity()).isEqualByComparingTo("5000");
    }

    @Test void dailyStopPaysSellCostsAndDoesNotForceEndLiquidation() {
        List<CandleEntity> bars = bars("A");
        set(bars, "A", start.plusDays(1), "90", "90");
        PortfolioRequest base = request(start.plusDays(1), false, 1);
        PortfolioRequest req = new PortfolioRequest(1, start, start.plusDays(1), SAME_DAY_CLOSE, bd("1000"), bd("0.01"), bd("0.02"), bd("0"), base.universe());
        PortfolioResult result = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, bd("-0.1")), req, bars);
        assertThat(result.trades()).hasSize(2);
        assertThat(result.trades().get(1).reason()).isEqualTo("STOP_LOSS");
        assertThat(result.cash()).isEqualByComparingTo("728.8");
        assertThat(result.holdings()).isEmpty();
    }

    @Test void missingCandidateExcludedButMissingHeldPriceFails() {
        List<CandleEntity> missing = bars("A");
        missing.removeIf(c -> date(c).equals(start));
        PortfolioResult excluded = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), request(start, false, 1), missing);
        assertThat(excluded.selections().get(0).excluded()).isNotEmpty();
        assertThat(excluded.trades()).isEmpty();
        List<CandleEntity> heldMissing = bars("A");
        heldMissing.removeIf(c -> date(c).equals(start.plusDays(1)));
        assertThatThrownBy(() -> engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), request(start.plusDays(1), false, 1), heldMissing))
                .isInstanceOf(UserStrategyBacktestEngine.DataException.class);
    }

    @Test void zeroVolumeNextFillFailsAndEndSignalRemainsPending() {
        List<CandleEntity> bars = bars("A");
        bars.stream().filter(c -> date(c).equals(start.plusDays(1))).forEach(c -> c.setVolume(BigDecimal.ZERO));
        assertThatThrownBy(() -> engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), request(start.plusDays(1), true, 1), bars))
                .isInstanceOf(UserStrategyBacktestEngine.DataException.class);
        PortfolioResult pending = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), request(start, true, 1), bars);
        assertThat(pending.pending()).hasSize(1);
        assertThat(pending.trades()).isEmpty();
    }

    @Test void membershipAndFuturePricesCannotEnterPastRanking() {
        PortfolioRequest base = request(start, false, 1);
        PortfolioRequest req = new PortfolioRequest(1, start, start, SAME_DAY_CLOSE, bd("1000"), bd("0"), bd("0"), bd("0"),
                new PortfolioRequest.Universe("historical-test", calendar, List.of(new PortfolioRequest.Member("A", Market.KOSPI, start.plusDays(1), null))));
        assertThat(engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), req, bars("A")).trades()).isEmpty();
        List<CandleEntity> original = bars("A");
        PortfolioResult before = engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), base, original);
        original.stream().filter(c -> date(c).isAfter(start)).forEach(c -> c.setClosePrice(bd("999999")));
        assertThat(engine.run(strategy(1, SelectionOrder.FILTER_THEN_RANK, null), base, original)).isEqualTo(before);
    }

    @Test void malformedInputsRejectMissingCostsUnsortedCalendarAndOverlappingMembership() {
        PortfolioRequest base = request(start, false, 1);
        assertThatThrownBy(() -> new PortfolioRequest(null, start, start, SAME_DAY_CLOSE, bd("1000"), null, bd("0"), bd("0"), base.universe()).requireValid(strategy(1, SelectionOrder.FILTER_THEN_RANK, null)))
                .isInstanceOf(StrategyValidationException.class);
        List<LocalDate> reverse = new ArrayList<>(calendar); Collections.reverse(reverse);
        assertThatThrownBy(() -> new PortfolioRequest(1, start, start, SAME_DAY_CLOSE, bd("1000"), bd("0"), bd("0"), bd("0"),
                new PortfolioRequest.Universe("test", reverse, base.universe().members())).requireValid(strategy(1, SelectionOrder.FILTER_THEN_RANK, null)))
                .isInstanceOf(StrategyValidationException.class);
        assertThatThrownBy(() -> new PortfolioRequest(1, start, start, SAME_DAY_CLOSE, bd("1000"), bd("0"), bd("0"), bd("0"),
                new PortfolioRequest.Universe("test", calendar, List.of(base.universe().members().get(0), base.universe().members().get(0)))).requireValid(strategy(1, SelectionOrder.FILTER_THEN_RANK, null)))
                .isInstanceOf(StrategyValidationException.class);
    }

    @Test void weeklyTurnoverSellsRankExitBeforeBuyingNewLeader() {
        var input=bars("A","B");
        set(input,"B",start,"110","110");
        var next=start.plusDays(7);set(input,"A",next,"130","130");set(input,"B",next,"150","150");
        var result=engine.run(strategy(1,SelectionOrder.FILTER_THEN_RANK,null),request(next,false,2),input);
        assertThat(result.selections()).hasSize(2);
        assertThat(result.trades()).extracting(PortfolioResult.Trade::side).containsExactly("BUY","SELL","BUY");
        assertThat(result.trades().get(1).reason()).isEqualTo("RANK_EXIT");
        assertThat(result.holdings()).extracting(PortfolioResult.Holding::symbol).containsExactly("B");
        assertThat(result.finalEquity()).isEqualByComparingTo("1080");
        assertThat(result.cash()).isEqualByComparingTo("30");
    }

    @Test void filterOrderChangesSelectionAndWeekEndUsesLastSessionBeforeHoliday() {
        var input=bars("A","B");set(input,"B",start.minusDays(3),"300","300");set(input,"B",start,"250","250");
        var before=engine.run(strategy(1,SelectionOrder.FILTER_THEN_RANK,null),request(start,false,2),input);
        var after=engine.run(strategy(1,SelectionOrder.RANK_THEN_FILTER,null),request(start,false,2),input);
        assertThat(before.trades()).extracting(PortfolioResult.Trade::symbol).containsExactly("A");
        assertThat(after.trades()).isEmpty();
        var thursday=start.plusDays(3);set(input,"A",thursday,"130","130");
        var base=request(thursday,false,1);
        var req=new PortfolioRequest(1,start,thursday,SAME_DAY_CLOSE,bd("1000"),bd("0"),bd("0"),bd("0"),new PortfolioRequest.Universe("holiday",calendar.stream().filter(d->!d.equals(start.plusDays(4))).toList(),base.universe().members()));
        var definition=new StrategyDefinition(3,"weekly",null,null,null,null,new RelativeStrength(Market.KOSPI,6,1,2,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_END,Weighting.EQUAL_SLOTS));
        var weekly=engine.run(definition,req,input);
        assertThat(weekly.selections()).extracting(PortfolioResult.Selection::date).containsExactly(thursday);
    }

    @Test void stopOnRebalanceDayRemovesCandidateFromEffectiveSelection() {
        var input=bars("A");var next=start.plusDays(7);set(input,"A",next,"110","110");
        var definition=new StrategyDefinition(3,"stop",null,null,null,new Risk(new StopLoss(bd("-0.08")),null,null,null),new RelativeStrength(Market.KOSPI,6,1,20,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_START,Weighting.EQUAL_SLOTS));
        var result=engine.run(definition,request(next,false,1),input);
        assertThat(result.selections().get(1).ranked().get(0).selected()).isFalse();
        assertThat(result.selections().get(1).excluded()).extracting(PortfolioResult.Excluded::reason).anyMatch(s->s.contains("손절"));
        assertThat(result.trades()).extracting(PortfolioResult.Trade::side).containsExactly("BUY","SELL");
    }

    StrategyDefinition strategy(int top, SelectionOrder order, BigDecimal stop) {
        return new StrategyDefinition(3, "test", "relative strength", null, null,
                stop == null ? null : new Risk(new StopLoss(stop), null, null, null),
                new RelativeStrength(Market.KOSPI, 6, top, 2, order, RebalanceTiming.WEEK_START, Weighting.EQUAL_SLOTS));
    }
    PortfolioRequest request(LocalDate end, boolean next, int count) {
        List<PortfolioRequest.Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) members.add(new PortfolioRequest.Member(String.valueOf((char)('A' + i)), Market.KOSPI, calendar.get(0), null));
        return new PortfolioRequest(1, start, end, next ? NEXT_DAY_OPEN : SAME_DAY_CLOSE, bd("1000"), bd("0"), bd("0"), bd("0"), new PortfolioRequest.Universe("historical-test", calendar, members));
    }
    List<CandleEntity> bars(String... symbols) {
        List<CandleEntity> bars = new ArrayList<>();
        for (String symbol : symbols) for (LocalDate d : calendar) {
            BigDecimal close = d.isBefore(start) ? bd("100") : bd("120");
            bars.add(new CandleEntity(symbol, d.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(), close, close, close, close, bd("10000")));
        }
        return bars;
    }
    void set(List<CandleEntity> bars, String symbol, LocalDate day, String open, String close) {
        bars.stream().filter(c -> c.getSymbol().equals(symbol) && date(c).equals(day)).forEach(c -> {
            c.setOpenPrice(bd(open)); c.setClosePrice(bd(close)); c.setHighPrice(bd(open).max(bd(close))); c.setLowPrice(bd(open).min(bd(close)));
        });
    }
    LocalDate date(CandleEntity c) { return Instant.ofEpochMilli(c.getTimestamp()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate(); }
    BigDecimal bd(String value) { return new BigDecimal(value); }
}
