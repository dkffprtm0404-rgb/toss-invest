package com.tossinvest.tossinvestbackend.strategypaper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.backtest.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;

class StrategyPaperEngineTests {
    final StrategyPaperEngine engine=new StrategyPaperEngine(new StrategyEvaluator());
    final StrategyJson json=new StrategyJson(new ObjectMapper().findAndRegisterModules());
    final LocalDate start=LocalDate.of(2025,1,2);

    @Test void macdAndBollingerConditionsMatchBacktestAndSurviveCheckpointReplay() throws Exception {
        for (String condition : List.of(
                "{\"type\":\"MACD\",\"fastPeriod\":1,\"slowPeriod\":3,\"signalPeriod\":2,\"value\":\"LINE\",\"reference\":\"THRESHOLD\",\"threshold\":0,\"comparison\":\"GTE\"}",
                "{\"type\":\"BOLLINGER\",\"period\":2,\"multiplier\":1,\"band\":\"UPPER\",\"comparison\":\"GTE\"}")) {
            var definition = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> json.read(
                    "{\"schemaVersion\":2,\"entry\":{\"conditions\":[" + condition + "]}}", StrategyDefinition.class));
            var input=bars(1,2,3,5,4,6);
            var candles=input.stream().map(b->new CandleEntity("005930",b.timestamp(),b.open(),b.high(),b.low(),b.close(),b.volume())).toList();
            for (var mode : UserStrategyBacktestRequest.ExecutionMode.values()) {
                var r=request(mode);r.validateStrategy(definition);
                var s=new PaperState(r.initialCapital(),start);
                engine.process(definition,r,s,input,Instant.parse("2025-02-01T00:00:00Z"));
                var backtest=new UserStrategyBacktestEngine(new StrategyEvaluator()).run(new UserStrategyBacktestRequest(
                        definition,"005930",start,LocalDate.of(2025,1,6),mode),candles);
                assertThat(s.trades).hasSize(1);
                assertThat(s.trades.get(0).evidence()).isEqualTo(backtest.openPosition().entry().evidence());
                assertThat(s.trades.get(0).executionDate()).isEqualTo(UserStrategyBacktestEngine.date(backtest.openPosition().entry().executionBarTimestamp()));
                var restored=json.stored(json.write(s),PaperState.class);
                engine.process(definition,r,restored,input,Instant.parse("2025-02-02T00:00:00Z"));
                assertThat(restored.trades).isEqualTo(s.trades);
            }
        }
    }

    @Test void trendTemplateMatchesBacktestAndCheckpointDoesNotDuplicateEntry() throws Exception {
        StrategyDefinition definition;
        try (var input = getClass().getResourceAsStream("/demo/strategy-trend-template.json")) {
            definition = json.read(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), StrategyDefinition.class);
        }
        var input = new ArrayList<StrategyBar>();
        for (int i = 0; i <= 366; i++) {
            var p = BigDecimal.valueOf(100 + i);
            input.add(new StrategyBar(LocalDate.of(2025, 1, 1).plusDays(i).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                    .toInstant().toEpochMilli(), p, p, BigDecimal.TEN, p, p));
        }
        var candles = input.stream().map(b -> new CandleEntity("005930", b.timestamp(), b.open(), b.high(), b.low(), b.close(), b.volume())).toList();
        var observedAt = Instant.parse("2026-01-04T00:00:00Z");
        for (var mode : UserStrategyBacktestRequest.ExecutionMode.values()) {
            var r = request(mode);
            r.validateStrategy(definition);
            var s = new PaperState(r.initialCapital(), LocalDate.of(2025, 12, 31));
            engine.process(definition, r, s, input.subList(0, 364), observedAt);
            assertThat(s.trades).isEmpty();
            engine.process(definition, r, s, input.subList(363, input.size()), observedAt);
            var backtest = new UserStrategyBacktestEngine(new StrategyEvaluator()).run(new UserStrategyBacktestRequest(
                    definition, "005930", LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2), mode), candles);
            assertThat(s.trades).hasSize(1);
            assertThat(s.trades.get(0).evidence()).hasSize(6).isEqualTo(backtest.openPosition().entry().evidence());
            assertThat(s.trades.get(0).executionDate()).isEqualTo(UserStrategyBacktestEngine.date(backtest.openPosition().entry().executionBarTimestamp()));
            var restored = json.stored(json.write(s), PaperState.class);
            engine.process(definition, r, restored, input, observedAt);
            assertThat(restored.trades).isEqualTo(s.trades);
        }
    }
    StrategyDefinition strategy() throws Exception {return json.read(StrategyPaperApiTests.STRATEGY,StrategyDefinition.class);}
    PaperDefinition request(UserStrategyBacktestRequest.ExecutionMode mode) {
        return new PaperDefinition(1L,1,"005930",mode,new BigDecimal("1000"),BigDecimal.ONE,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,UUID.randomUUID().toString());
    }
    static List<StrategyBar> bars(int...prices) {
        var result=new ArrayList<StrategyBar>();
        for(int i=0;i<prices.length;i++) {var p=BigDecimal.valueOf(prices[i]);result.add(new StrategyBar(
            LocalDate.of(2025,1,1).plusDays(i).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),p,p,BigDecimal.TEN,p,p));}
        return result;
    }
    void apply(PaperState state,PaperDefinition r,List<StrategyBar> bars) throws Exception {
        engine.process(strategy(),r,state,bars,Instant.parse("2025-02-01T00:00:00Z"));
    }
    @Test void closeModeMatchesBacktestAndPreservesAccountAcrossRefresh() throws Exception {
        var r=request(SAME_DAY_CLOSE); var s=new PaperState(r.initialCapital(),start); var input=bars(100,100,94,93);
        apply(s,r,input.subList(0,2)); assertThat(s.position.quantity()).isEqualTo(10);assertThat(s.cash).isEqualByComparingTo("0");
        s.stop(Instant.parse("2025-01-03T00:00:00Z"));apply(s,r,input);
        assertThat(s.status).isEqualTo("STOPPED");assertThat(s.trades).hasSize(2);
        assertThat(s.cash).isEqualByComparingTo("940");assertThat(s.realizedPnl).isEqualByComparingTo("-60");
        assertThat(s.maxDrawdown).isEqualByComparingTo("0.06");
        apply(s,r,input);assertThat(s.trades).hasSize(2);
        var candles=input.stream().map(b->new CandleEntity("005930",b.timestamp(),b.open(),b.high(),b.low(),b.close(),b.volume())).toList();
        var result=new UserStrategyBacktestEngine(new StrategyEvaluator()).run(new UserStrategyBacktestRequest(strategy(),"005930",start,start.plusDays(1),SAME_DAY_CLOSE),candles);
        assertThat(s.trades.get(1).reason()).isEqualTo(result.trades().get(0).exit().reason());
        assertThat(s.trades.get(1).price()).isEqualByComparingTo(result.trades().get(0).exit().price());
    }
    @Test void nextOpenQuantityUsesOpenAndStopCancelsPendingBuy() throws Exception {
        var r=request(NEXT_DAY_OPEN);var s=new PaperState(r.initialCapital(),start);
        apply(s,r,bars(100,100));assertThat(s.pending.side()).isEqualTo("BUY");
        var input=new ArrayList<>(bars(100,100,200));var third=input.get(2);
        input.set(2,new StrategyBar(third.timestamp(),new BigDecimal("50"),new BigDecimal("200"),BigDecimal.TEN,new BigDecimal("200"),new BigDecimal("50")));
        apply(s,r,input);assertThat(s.trades.get(0).quantity()).isEqualTo(20);assertThat(s.trades.get(0).price()).isEqualByComparingTo("50");
        var stopped=new PaperState(r.initialCapital(),start);apply(stopped,r,bars(100,100));stopped.stop(Instant.now());apply(stopped,r,input);
        assertThat(stopped.trades).isEmpty();assertThat(stopped.pending).isNull();assertThat(stopped.status).isEqualTo("STOPPED");
    }
    @Test void costsIntegerSizingAndInsufficientCashAreExplicit() throws Exception {
        var r=new PaperDefinition(1L,1,"005930",SAME_DAY_CLOSE,new BigDecimal("1000"),BigDecimal.ONE,new BigDecimal("0.01"),new BigDecimal("0.02"),BigDecimal.ZERO,UUID.randomUUID().toString());
        var s=new PaperState(r.initialCapital(),start);apply(s,r,bars(100,100));
        assertThat(s.position.quantity()).isEqualTo(9);assertThat(s.cash).isEqualByComparingTo("91");
        s.stop(Instant.now());apply(s,r,bars(100,100,94));assertThat(s.cash).isEqualByComparingTo("911.62");assertThat(s.realizedPnl).isEqualByComparingTo("-88.38");
        var tiny=new PaperState(new BigDecimal("1"),start);apply(tiny,r,bars(100,100));assertThat(tiny.trades).isEmpty();assertThat(tiny.dataStatus).isEqualTo("INSUFFICIENT_CASH");
    }
    @Test void rejectsRevisedHistoryAndDoesNotTradeTodayOrBeforeStart() throws Exception {
        var r=request(SAME_DAY_CLOSE);var s=new PaperState(r.initialCapital(),start.plusDays(5));apply(s,r,bars(100,100));
        assertThat(s.trades).isEmpty();
        assertThatThrownBy(()->apply(s,r,bars(101,100))).hasMessageContaining("변경");
        var today=new PaperState(r.initialCapital(),start);
        engine.process(strategy(),r,today,bars(100,100),Instant.parse("2025-01-02T10:00:00Z"));assertThat(today.trades).isEmpty();
    }
    @Test void zeroVolumeCannotFillPendingOrder() throws Exception {
        var r=request(NEXT_DAY_OPEN);var s=new PaperState(r.initialCapital(),start);apply(s,r,bars(100,100));
        var input=new ArrayList<>(bars(100,100,100));var b=input.get(2);input.set(2,new StrategyBar(b.timestamp(),b.open(),b.close(),BigDecimal.ZERO,b.high(),b.low()));
        assertThatThrownBy(()->apply(s,r,input)).hasMessageContaining("거래량");
    }
    @Test void allRiskPoliciesMatchBacktestAcrossBothFillModes() throws Exception {
        var input=bars(100,102,103,106,110,105,100,96,102,105,104,90,100,100,106,103,99);
        var candles=input.stream().map(b->new CandleEntity("005930",b.timestamp(),b.open(),b.high(),b.low(),b.close(),b.volume())).toList();
        for(var mode:UserStrategyBacktestRequest.ExecutionMode.values()) for(String risk:List.of(
                "{\"stopLoss\":{\"rate\":-0.05}}", "{\"takeProfit\":{\"rate\":0.03}}", "{\"timeExit\":{\"days\":1}}",
                "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}","{\"trailingStop\":{\"rate\":-0.05,\"peakBasis\":\"HIGH\"}}",
                "{\"atrStop\":{\"method\":\"SIMPLE\",\"period\":1,\"multiplier\":2}}")) {
            var definition=json.read(StrategyPaperApiTests.STRATEGY.replace("\"schemaVersion\":1","\"schemaVersion\":2").replace("{\"stopLoss\":{\"rate\":-0.05}}",risk),StrategyDefinition.class);
            var r=request(mode);var s=new PaperState(r.initialCapital(),start);
            engine.process(definition,r,s,input,Instant.parse("2025-02-01T00:00:00Z"));
            var backtest=new UserStrategyBacktestEngine(new StrategyEvaluator()).run(new UserStrategyBacktestRequest(definition,"005930",start,LocalDate.of(2025,1,17),mode),candles);
            var sells=s.trades.stream().filter(t->t.side().equals("SELL")).toList();
            assertThat(sells).as("%s / %s",mode,risk).hasSize(backtest.trades().size());
            for(int i=0;i<sells.size();i++) {assertThat(sells.get(i).reason()).isEqualTo(backtest.trades().get(i).exit().reason());assertThat(sells.get(i).price()).isEqualByComparingTo(backtest.trades().get(i).exit().price());}
        }
    }
}
