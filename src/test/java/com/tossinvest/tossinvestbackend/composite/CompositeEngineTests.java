package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.CandleEntity;
import com.tossinvest.tossinvestbackend.portfolio.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static org.assertj.core.api.Assertions.*;

class CompositeEngineTests {
    final CompositeEngine engine = new CompositeEngine();
    final LocalDate start = LocalDate.of(2025,7,7);
    final List<LocalDate> calendar = LocalDate.of(2025,5,1).datesUntil(LocalDate.of(2025,7,26))
            .filter(d -> d.getDayOfWeek().getValue() <= 5).toList();

    @Test void readyOrBranchBuysOnceAndRetainsUnreadyLeafEvidence() {
        var rule = group("or", Operator.OR, leaf("fast", 2, Comparison.GT), leaf("slow", 200, Comparison.GT));
        var result = engine.run(strategy(rule,null,null,null,1,Rebalance.ENTRY_ONLY), request(start,false,"A",false), bars("A"));
        assertThat(result.account().trades()).hasSize(1);
        assertThat(result.account().trades().get(0).quantity()).isEqualTo(8);
        assertThat(result.evaluations().stream().filter(e -> e.phase().equals("ENTRY")).findFirst().orElseThrow().nodes())
                .anySatisfy(n -> { assertThat(n.id()).isEqualTo("slow"); assertThat(n.ready()).isFalse(); });
    }

    @Test void nestedAndKeepsFilterScopeAndUnknownDoesNotPass() {
        var rule = group("all",Operator.AND,group("either",Operator.OR,leaf("fast",2,Comparison.GT),leaf("slow",200,Comparison.GT)),leaf("filter",2,Comparison.LT));
        assertThat(engine.run(strategy(rule,null,null,null,1,Rebalance.ENTRY_ONLY),request(start,false,"A",false),bars("A")).account().trades()).isEmpty();
        rule = group("all",Operator.AND,leaf("fast",2,Comparison.GT),leaf("slow",200,Comparison.GT));
        var result = engine.run(strategy(rule,null,null,null,1,Rebalance.ENTRY_ONLY),request(start,false,"A",false),bars("A"));
        assertThat(result.evaluations().stream().filter(e -> e.phase().equals("ENTRY")).findFirst().orElseThrow().ready()).isFalse();
    }

    @Test void stopAndExitOverlapSellOnceAndPreventSameDayReentry() {
        var input=bars("A");set(input,"A",start.plusDays(1),"90","90");
        var risk=new Risk(new StopLoss(bd("-0.1")),null,null,null,new TrailingStop(bd("-0.1"),PeakBasis.CLOSE),null);
        var result=engine.run(strategy(leaf("entry",2,Comparison.GTE),leaf("exit",2,Comparison.LTE),risk,null,1,Rebalance.ENTRY_ONLY),request(start.plusDays(1),false,"A",false),input);
        assertThat(result.account().trades()).extracting(PortfolioResult.Trade::side).containsExactly("BUY","SELL");
        assertThat(result.account().trades().get(1).reason()).isEqualTo("STOP_LOSS");
        assertThat(result.evaluations().stream().filter(e->e.date().equals(start.plusDays(1)) && e.matched()).map(CompositeResult.Evaluation::reason))
                .contains("STOP_LOSS","TRAILING_STOP","EXIT_CONDITIONS");
        assertThat(result.account().cash()).isEqualByComparingTo("760");
    }

    @Test void unreadyExitDoesNotBlockStopAndEndOpenOrderIsPending() {
        var input=bars("A");set(input,"A",start.plusDays(1),"90","90");
        var strategy=strategy(leaf("entry",2,Comparison.GTE),leaf("exit",200,Comparison.LT),new Risk(new StopLoss(bd("-0.1")),null,null,null),null,1,Rebalance.ENTRY_ONLY);
        assertThat(engine.run(strategy,request(start.plusDays(1),false,"A",false),input).account().trades()).hasSize(2);
        var pending=engine.run(strategy,request(start,true,"A",false),input);
        assertThat(pending.account().pending()).singleElement().satisfies(p -> assertThat(p.reason()).contains("NO_NEXT_BAR"));
        assertThat(pending.account().trades()).isEmpty();
    }

    @Test void nextOpenQuantityAndFrozenAtrCannotReadExecutionDayClose() {
        var input=bars("A");set(input,"A",start.plusDays(1),"200","1000");
        var strategy=strategy(leaf("entry",2,Comparison.GTE),null,new Risk(null,null,null,null,null,new AtrStop(AtrMethod.SIMPLE,2,bd("2"))),null,1,Rebalance.ENTRY_ONLY);
        var result=engine.run(strategy,request(start.plusDays(1),true,"A",false),input);
        assertThat(result.account().trades().get(0).quantity()).isEqualTo(5);
        assertThat(result.account().finalEquity()).isEqualByComparingTo("5000");
    }

    @Test void weeklyCandidatesWaitForDailyEntryAndLeaveUnusedSlotsInCash() {
        var input=bars("A","B");set(input,"A",start.minusDays(3),"110","110");set(input,"A",start,"105","105");set(input,"A",start.plusDays(1),"130","130");
        var rule=new PriceMovingAverage(AverageType.SMA,2,Comparison.GT);
        var selection=new RelativeStrength(Market.KOSPI,1,2,20,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_START,Weighting.EQUAL_SLOTS);
        var result=engine.run(strategy(new RuleNode("entry","source",rule,null,null),null,null,selection,2,Rebalance.ENTRY_ONLY),request(start.plusDays(1),false,null,true),input);
        assertThat(result.account().selections()).hasSize(1);
        assertThat(result.account().trades()).extracting(PortfolioResult.Trade::symbol).containsExactly("B","A");
        assertThat(result.account().trades().get(1).date()).isEqualTo(start.plusDays(1));
        assertThat(result.account().equity().get(0).cash()).isEqualByComparingTo("520");
        assertThat(result.account().cash()).isEqualByComparingTo("130");
    }

    @Test void selectedSymbolUsesComparisonUniverseAndNeverTradesOtherSymbols() {
        var input=bars("A","B");set(input,"B",start,"150","150");
        var result=engine.run(strategy(leaf("entry",2,Comparison.GT),null,null,selection(1),1,Rebalance.ENTRY_ONLY),request(start,false,"A",true),input);
        assertThat(result.account().selections().get(0).ranked()).extracting(PortfolioResult.Ranked::symbol).containsExactly("B","A");
        assertThat(result.account().trades()).isEmpty();
    }

    @Test void integerCostsAndNoForcedLiquidation() {
        var req=new CompositeRequest(1,start,start,SAME_DAY_CLOSE,bd("1000"),bd("0.01"),bd("0.02"),bd("0.1"),"A",null);
        var result=engine.run(strategy(leaf("entry",2,Comparison.GT),null,null,null,1,Rebalance.ENTRY_ONLY),req,bars("A"));
        assertThat(result.account().trades().get(0).quantity()).isEqualTo(7);
        assertThat(result.account().cash()).isEqualByComparingTo("66.76");
        assertThat(result.account().holdings()).hasSize(1);
    }

    @Test void weeklyPartialSalePreservesPeakAndFirstAtrWhileAdditionalBuyUpdatesAverage() {
        var input=bars("A","B");
        input.stream().filter(c->date(c).isBefore(start)).forEach(c->{c.setHighPrice(bd("110"));c.setLowPrice(bd("90"));});
        var next=start.plusDays(7);set(input,"A",next,"240","240");set(input,"B",next,"150","150");
        set(input,"A",next.plusDays(1),"180","180");set(input,"B",next.plusDays(1),"140","140");
        var risk=new Risk(null,null,null,null,new TrailingStop(bd("-0.2"),PeakBasis.CLOSE),new AtrStop(AtrMethod.SIMPLE,2,bd("2")));
        var result=engine.run(strategy(leaf("entry",2,Comparison.GTE),null,risk,selection(2),2,Rebalance.WEEKLY_EQUAL),request(next.plusDays(1),false,null,true),input);
        assertThat(result.account().trades().stream().filter(t->t.symbol().equals("A")&&t.side().equals("SELL")))
                .extracting(PortfolioResult.Trade::quantity).containsExactly(1L,3L);
        assertThat(result.account().trades().get(2).reason()).isEqualTo("REBALANCE");
        assertThat(result.account().trades().get(4).reason()).isEqualTo("TRAILING_STOP");
        assertThat(result.account().holdings()).singleElement().satisfies(h->{assertThat(h.symbol()).isEqualTo("B");assertThat(h.averageEntry()).isEqualByComparingTo("126");assertThat(h.quantity()).isEqualTo(5);});
        assertThat(result.evaluations().stream().filter(e->e.date().equals(next.plusDays(1))&&e.symbol().equals("A")&&e.reason().equals("ATR_STOP_LOSS")).findFirst().orElseThrow().nodes().get(0).evidence().get(0).referenceValue()).isEqualByComparingTo("80");
    }

    @Test void averagePriceStopUsesAdditionalBuyAndEntryOnlyNeverResizesHoldings() {
        var input=bars("A","B");var next=start.plusDays(7);set(input,"A",next,"240","240");set(input,"B",next,"150","150");
        set(input,"A",next.plusDays(1),"240","240");set(input,"B",next.plusDays(1),"112","112");
        var risk=new Risk(new StopLoss(bd("-0.1")),null,null,null);
        var weekly=engine.run(strategy(leaf("entry",2,Comparison.GTE),null,risk,selection(2),2,Rebalance.WEEKLY_EQUAL),request(next.plusDays(1),false,null,true),input);
        assertThat(weekly.account().trades().stream().filter(t->t.symbol().equals("B")&&t.side().equals("SELL"))).singleElement().satisfies(t->{assertThat(t.reason()).isEqualTo("STOP_LOSS");assertThat(t.quantity()).isEqualTo(5);});
        var entryOnly=engine.run(strategy(leaf("entry",2,Comparison.GTE),null,risk,selection(2),2,Rebalance.ENTRY_ONLY),request(next.plusDays(1),false,null,true),input);
        assertThat(entryOnly.account().trades()).hasSize(2);
        assertThat(entryOnly.account().holdings()).allSatisfy(h->assertThat(h.quantity()).isEqualTo(4));
    }

    @Test void missingFillIsEvidenceButMissingHeldValuationFails() {
        var input=bars("A","B");input.removeIf(c->c.getSymbol().equals("A")&&date(c).equals(start.plusDays(1)));
        var strategy=strategy(leaf("entry",2,Comparison.GT),null,null,selection(2),2,Rebalance.ENTRY_ONLY);
        var result=engine.run(strategy,request(start.plusDays(1),true,null,true),input);
        assertThat(result.evaluations()).anySatisfy(e->{assertThat(e.symbol()).isEqualTo("A");assertThat(e.reason()).isEqualTo("NO_EXECUTION_BAR");});
        assertThatThrownBy(()->engine.run(strategy,request(start.plusDays(1),false,null,true),input)).isInstanceOf(com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine.DataException.class);
    }

    @Test void rejectedMissingComparisonUniverseCostsAndNonSelectedSymbols() {
        var strategy=strategy(leaf("entry",2,Comparison.GT),null,null,selection(1),1,Rebalance.ENTRY_ONLY);
        assertThatThrownBy(()->request(start,false,"A",false).requireValid(strategy)).isInstanceOf(StrategyValidationException.class);
        assertThatThrownBy(()->request(start,false,"C",true).requireValid(strategy)).isInstanceOf(StrategyValidationException.class);
        var req=new CompositeRequest(1,start,start,SAME_DAY_CLOSE,bd("1000"),null,bd("0"),bd("0"),"A",null);
        assertThatThrownBy(()->req.requireValid(strategy(leaf("entry",2,Comparison.GT),null,null,null,1,Rebalance.ENTRY_ONLY))).isInstanceOf(StrategyValidationException.class);
    }

    StrategyDefinition strategy(RuleNode entry, RuleNode exit, Risk risk, RelativeStrength selection,int slots,Rebalance rebalance) {
        var original=new StrategyDefinition(2,"source","source",new ConditionGroup(Operator.AND,List.of(new PriceMovingAverage(AverageType.SMA,2,Comparison.GT))),null,null);
        var source=new Source("source","source","source",original,List.of(),List.of(),true,"공통 조건과 위험 관리 변경 확인");
        var sources=new ArrayList<Source>();sources.add(source);
        if(selection!=null) sources.add(new Source("selection","selection","selection",new StrategyDefinition(3,"selection","selection",null,null,null,selection),List.of(),List.of(),true,"confirmed"));
        return new StrategyDefinition(4,"composite","test",null,null,risk,null,new CompositionDefinition(sources,entry,exit,selection,selection==null?null:"selection",selection==null?null:false,new Allocation(slots,rebalance),true,true));
    }
    RuleNode leaf(String id,int period,Comparison comparison){return new RuleNode(id,"source",new PriceMovingAverage(AverageType.SMA,period,comparison),null,null);}
    RuleNode group(String id,Operator operator,RuleNode...children){return new RuleNode(id,null,null,operator,List.of(children));}
    RelativeStrength selection(int top){return new RelativeStrength(Market.KOSPI,1,top,2,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_START,Weighting.EQUAL_SLOTS);}
    CompositeRequest request(LocalDate end,boolean next,String symbol,boolean universe){return new CompositeRequest(1,start,end,next?NEXT_DAY_OPEN:SAME_DAY_CLOSE,bd("1000"),bd("0"),bd("0"),bd("0"),symbol,universe?new PortfolioRequest.Universe("historical",calendar,List.of(new PortfolioRequest.Member("A",Market.KOSPI,calendar.get(0),null),new PortfolioRequest.Member("B",Market.KOSPI,calendar.get(0),null))):null);}
    List<CandleEntity> bars(String...symbols){var result=new ArrayList<CandleEntity>();for(var s:symbols)for(var d:calendar){var price=bd(d.isBefore(start)?"100":"120");result.add(new CandleEntity(s,d.atStartOfDay(UserStrategyBacktestEngineZone.ZONE).toInstant().toEpochMilli(),price,price,price,price,bd("10000")));}return result;}
    void set(List<CandleEntity> bars,String symbol,LocalDate date,String open,String close){bars.stream().filter(b->b.getSymbol().equals(symbol)&&Instant.ofEpochMilli(b.getTimestamp()).atZone(UserStrategyBacktestEngineZone.ZONE).toLocalDate().equals(date)).forEach(b->{b.setOpenPrice(bd(open));b.setClosePrice(bd(close));b.setHighPrice(bd(open).max(bd(close)));b.setLowPrice(bd(open).min(bd(close)));});}
    BigDecimal bd(String value){return new BigDecimal(value);}
    LocalDate date(CandleEntity c){return Instant.ofEpochMilli(c.getTimestamp()).atZone(UserStrategyBacktestEngineZone.ZONE).toLocalDate();}
    static class UserStrategyBacktestEngineZone {static final ZoneId ZONE=ZoneId.of("Asia/Seoul");}
}
