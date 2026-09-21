package com.tossinvest.tossinvestbackend.composite;

import org.junit.jupiter.api.Test;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static org.assertj.core.api.Assertions.*;

class CompositeLifecycleTests {
    private final CompositeEngineTests f=new CompositeEngineTests();
    @Test void partialWeeklySalePreservesPeakAndFrozenAtrPrice() {
        var input=f.bars("A","B");var nextWeek=f.start.plusDays(7);
        input.stream().filter(c->c.getTimestamp()<f.start.atStartOfDay(CompositeEngineTests.UserStrategyBacktestEngineZone.ZONE).toInstant().toEpochMilli()).forEach(c->{c.setHighPrice(f.bd("102"));c.setLowPrice(f.bd("98"));});
        // Both enter at 120. A grows, then weekly allocation sells part of A to add B.
        for(var d:f.calendar)if(d.isAfter(f.start)&&d.isBefore(nextWeek))f.set(input,"A",d,"200","200");
        f.set(input,"A",nextWeek,"240","240");f.set(input,"A",nextWeek.plusDays(1),"185","185");
        var risk=new Risk(null,null,null,null,new TrailingStop(f.bd("-0.20"),PeakBasis.CLOSE),new AtrStop(AtrMethod.SIMPLE,2,f.bd("1.5")));
        var strategy=f.strategy(f.leaf("entry",2,Comparison.GT),null,risk,f.selection(2),2,Rebalance.WEEKLY_EQUAL);
        var r=f.engine.run(strategy,f.request(nextWeek.plusDays(1),false,null,true),input);
        assertThat(r.account().trades()).anySatisfy(t->{assertThat(t.symbol()).isEqualTo("A");assertThat(t.date()).isEqualTo(nextWeek);assertThat(t.side()).isEqualTo("SELL");assertThat(t.quantity()).isEqualTo(1);assertThat(t.reason()).isEqualTo("REBALANCE");});
        assertThat(r.account().trades()).anySatisfy(t->{assertThat(t.symbol()).isEqualTo("A");assertThat(t.date()).isEqualTo(nextWeek.plusDays(1));assertThat(t.reason()).isEqualTo("TRAILING_STOP");assertThat(t.quantity()).isEqualTo(3);});
        assertThat(r.evaluations().stream().filter(e->e.symbol().equals("A")&&e.reason().equals("ATR_STOP_LOSS")).flatMap(e->e.nodes().stream()).flatMap(n->n.evidence().stream()))
                .allSatisfy(e->assertThat(e.referenceValue()).isEqualByComparingTo("114"));
        assertThat(r.evaluations().stream().filter(e->e.date().equals(nextWeek.plusDays(1))&&e.symbol().equals("A")&&e.reason().equals("TRAILING_STOP")).flatMap(e->e.nodes().stream()).flatMap(n->n.evidence().stream()))
                .singleElement().satisfies(e->assertThat(e.referenceValue()).isEqualByComparingTo("192"));
    }
    @Test void weeklyAdditionChangesAverageButNeverReanchorsAtr() {
        var input=f.bars("A","B");var nextWeek=f.start.plusDays(7);
        input.stream().filter(c->c.getTimestamp()<f.start.atStartOfDay(CompositeEngineTests.UserStrategyBacktestEngineZone.ZONE).toInstant().toEpochMilli()).forEach(c->{c.setHighPrice(f.bd("102"));c.setLowPrice(f.bd("98"));});
        for(var d:f.calendar)if(d.isAfter(f.start)&&!d.isAfter(nextWeek))f.set(input,"B",d,"240","240");
        f.set(input,"A",nextWeek,"125","125");f.set(input,"A",nextWeek.plusDays(1),"130","130");
        var risk=new Risk(new StopLoss(f.bd("-0.05")),null,null,null,null,new AtrStop(AtrMethod.SIMPLE,2,f.bd("1.5")));
        var r=f.engine.run(f.strategy(f.leaf("entry",2,Comparison.GT),null,risk,f.selection(2),2,Rebalance.WEEKLY_EQUAL),f.request(nextWeek.plusDays(1),false,null,true),input);
        assertThat(r.account().trades()).anySatisfy(t->{assertThat(t.symbol()).isEqualTo("A");assertThat(t.date()).isEqualTo(nextWeek);assertThat(t.side()).isEqualTo("BUY");assertThat(t.reason()).isEqualTo("REBALANCE");});
        assertThat(r.account().holdings()).anySatisfy(h->{assertThat(h.symbol()).isEqualTo("A");assertThat(h.averageEntry()).isGreaterThan(f.bd("120")).isLessThan(f.bd("125"));});
        assertThat(r.evaluations().stream().filter(e->e.symbol().equals("A")&&e.reason().equals("ATR_STOP_LOSS")).flatMap(e->e.nodes().stream()).flatMap(n->n.evidence().stream()))
                .allSatisfy(e->assertThat(e.referenceValue()).isEqualByComparingTo("114"));
    }
    @Test void aWeeklyCandidateCanEnterLaterInTheWeekWithoutRequiringAnotherSelection() {
        var input=f.bars("A");f.set(input,"A",f.start,"110","110");f.set(input,"A",f.start.plusDays(1),"130","130");
        var condition=new RangeBreakout(2,PeriodUnit.BARS,PriceField.HIGH,Comparison.CROSS_ABOVE);
        // Monday is selected but stays below Friday's high; Tuesday finally breaks it.
        input.stream().filter(c->c.getTimestamp()==f.start.minusDays(3).atStartOfDay(CompositeEngineTests.UserStrategyBacktestEngineZone.ZONE).toInstant().toEpochMilli()).forEach(c->c.setHighPrice(f.bd("120")));
        var leaf=new com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.RuleNode("breakout","source",condition,null,null);
        var result=f.engine.run(f.strategy(leaf,null,null,f.selection(1),1,Rebalance.ENTRY_ONLY),f.request(f.start.plusDays(1),false,"A",true),input);
        assertThat(result.account().selections()).hasSize(1);
        assertThat(result.account().trades()).singleElement().satisfies(t->assertThat(t.date()).isEqualTo(f.start.plusDays(1)));
    }
}
