package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.backtest.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MacdBollingerStrategyTests {
    final StrategyJson json = new StrategyJson(new ObjectMapper());
    final StrategyEvaluator evaluator = new StrategyEvaluator();
    final StrategyValidator validator = new StrategyValidator();
    static final String MACD = "{\"type\":\"MACD\",\"fastPeriod\":1,\"slowPeriod\":3,\"signalPeriod\":2,\"value\":\"LINE\",\"reference\":\"THRESHOLD\",\"threshold\":0,\"comparison\":\"GTE\"}";
    static final String BOLLINGER = "{\"type\":\"BOLLINGER\",\"period\":2,\"multiplier\":1,\"band\":\"UPPER\",\"comparison\":\"GTE\"}";

    @Test void macdLineSignalAndHistogramHaveIndependentHandCalculatedValues() {
        var line = prepare(MACD, 1,2,3,5);
        assertThat(line.entryReady(1)).isFalse();
        assertThat(line.entryReady(2)).isTrue();
        assertThat(line.entry(2).evidence().get(0).actualValue()).isEqualByComparingTo("1");
        var signal = prepare(MACD.replace("\"LINE\"", "\"SIGNAL\""), 1,2,3,5);
        assertThat(signal.entryReady(2)).isFalse();
        assertThat(signal.entry(3).evidence().get(0).actualValue()).isEqualByComparingTo("1.25");
        var histogram = prepare(MACD.replace("\"LINE\"", "\"HISTOGRAM\""), 1,2,3,5);
        assertThat(histogram.entry(3).evidence().get(0).actualValue()).isEqualByComparingTo("0.25");
        assertThat(histogram.entry(3).evidence().get(0).type()).isEqualTo("MACD");
    }

    @Test void macdSignalCrossWaitsForBothPreviousSeriesAndUsesExplicitDirection() {
        String above = MACD.replace("\"THRESHOLD\"", "\"SIGNAL\"").replace("\"threshold\":0", "\"threshold\":null").replace("\"GTE\"", "\"CROSS_ABOVE\"");
        var evaluation = prepare(above,1,2,3,5,4,6);
        assertThat(evaluation.requiredWarmupBars()).isEqualTo(4);
        assertThat(evaluation.entryReady(3)).isFalse();
        assertThat(evaluation.entry(4).matched()).isFalse();
        assertThat(evaluation.entry(5).matched()).isTrue();
        var evidence = evaluation.entry(5).evidence().get(0);
        assertThat(evidence.actualValue()).isEqualByComparingTo("1.125");
        assertThat(evidence.previousActualValue()).isEqualByComparingTo("0.25");
        assertThat(evidence.previousReferenceValue()).isCloseTo(new BigDecimal("0.583333333333"), within(new BigDecimal("0.00000000001")));
        assertThat(prepare(above.replace("CROSS_ABOVE","CROSS_BELOW"),1,2,3,5,4,6).entry(4).matched()).isTrue();
    }

    @Test void numericMacdThresholdsAllowZeroAndNegativeValuesWithoutWaitingForSignal() {
        assertThat(validator.validate(strategy(MACD))).isEmpty();
        var evaluation = prepare(MACD.replace("\"threshold\":0","\"threshold\":-1"),1,2,3);
        assertThat(evaluation.entryReady(2)).isTrue();
        assertThat(evaluation.entry(2).evidence().get(0).referenceValue()).isEqualByComparingTo("-1");
    }

    @Test void missingParametersAreReportedWithoutChoosingUsualIndicatorDefaults() {
        var missingMacd = strategy("{\"type\":\"MACD\"}");
        assertThat(validator.validate(missingMacd)).extracting(StrategyValidator.Issue::path).contains(
                "entry.conditions[0].fastPeriod","entry.conditions[0].slowPeriod","entry.conditions[0].signalPeriod",
                "entry.conditions[0].value","entry.conditions[0].reference","entry.conditions[0].comparison");
        assertThat(validator.validate(strategy("{\"type\":\"BOLLINGER\"}"))).extracting(StrategyValidator.Issue::path).contains(
                "entry.conditions[0].period","entry.conditions[0].multiplier","entry.conditions[0].band","entry.conditions[0].comparison");
        assertThat(validator.validate(strategy(MACD.replace("\"threshold\":0","\"threshold\":null")))).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0].threshold");
    }

    @Test void conflictingMacdReferencesAndInvalidPeriodsCannotBeSaved() {
        for(String condition:List.of(MACD.replace("\"fastPeriod\":1","\"fastPeriod\":3"),MACD.replace("\"signalPeriod\":2","\"signalPeriod\":0"),MACD.replace("\"reference\":\"THRESHOLD\"","\"reference\":\"SIGNAL\""),MACD.replace("\"value\":\"LINE\"","\"value\":\"HISTOGRAM\"").replace("\"reference\":\"THRESHOLD\"","\"reference\":\"SIGNAL\"").replace("\"threshold\":0","\"threshold\":null"),BOLLINGER.replace("\"multiplier\":1","\"multiplier\":0"))) {
            assertThat(validator.validate(strategy(condition))).isNotEmpty();
        }
    }

    @Test void bollingerUsesCurrentCloseAndPopulationDeviationForAllThreeBands() {
        var upper = prepare(BOLLINGER.replace("\"period\":2","\"period\":3").replace("\"multiplier\":1","\"multiplier\":2"),1,2,3);
        var evidence=upper.entry(2).evidence().get(0);
        assertThat(evidence.actualValue()).isEqualByComparingTo("3");
        assertThat(evidence.referenceValue()).isCloseTo(new BigDecimal("3.632993161855"),within(new BigDecimal("0.00000000001")));
        assertThat(evidence.matched()).isFalse();
        assertThat(evidence.type()).isEqualTo("BOLLINGER");
        assertThat(prepare(BOLLINGER.replace("UPPER","MIDDLE"),10,14).entry(1).evidence().get(0).referenceValue()).isEqualByComparingTo("12");
        assertThat(prepare(BOLLINGER.replace("UPPER","LOWER"),14,10).entry(1).evidence().get(0).referenceValue()).isEqualByComparingTo("10");
        assertThat(upper.entryReady(1)).isFalse();
    }

    @Test void bollingerEqualityIsInclusiveButNotAFreshCross() {
        for(String comparison:List.of("GTE","LTE","GT","LT","CROSS_ABOVE","CROSS_BELOW")) {
            var evaluation=prepare(BOLLINGER.replace("GTE",comparison),10,10,14);
            assertThat(evaluation.entry(2).matched()).as(comparison).isEqualTo(comparison.equals("GTE")||comparison.equals("LTE"));
        }
    }

    @Test void bollingerCrossComparesBothRollingBandsAndWaitsForPreviousWindow() {
        String above=BOLLINGER.replace("\"multiplier\":1","\"multiplier\":0.5").replace("GTE","CROSS_ABOVE");
        var evaluation=prepare(above,10,8,14);
        assertThat(evaluation.entryReady(1)).isFalse();
        assertThat(evaluation.entry(2).matched()).isTrue();
        var evidence=evaluation.entry(2).evidence().get(0);
        assertThat(evidence.referenceValue()).isEqualByComparingTo("12.5");
        assertThat(evidence.previousReferenceValue()).isEqualByComparingTo("9.5");
        assertThat(prepare(above.replace("UPPER","LOWER").replace("CROSS_ABOVE","CROSS_BELOW"),8,10,4).entry(2).matched()).isTrue();
    }

    @Test void futureBarsCannotChangeEarlierIndicatorDecisions() {
        for(String condition:List.of(MACD,BOLLINGER)) {
            assertThat(prepare(condition,1,2,3,5,4,6,1000,1).entry(3)).isEqualTo(prepare(condition,1,2,3,5).entry(3));
        }
    }

    @Test void newConditionsRoundTripAndRemainVersionTwoOnly() {
        for(String condition:List.of(MACD,BOLLINGER)) {
            var definition=strategy(condition);
            assertThat(json.stored(json.write(definition),StrategyDefinition.class)).isEqualTo(definition);
            var legacy=json.stored(json.write(definition).replace("\"schemaVersion\":2","\"schemaVersion\":1"),StrategyDefinition.class);
            assertThat(validator.validate(legacy)).extracting(StrategyValidator.Issue::path).contains("schemaVersion");
        }
    }

    @Test void bothIndicatorsReachRealBacktestFillModesAndCompositeLeafEvaluation() {
        for(String condition:List.of(MACD,BOLLINGER)) {
            var definition=strategy(condition);
            var input=bars(1,2,3,5,4,6);
            var candles=input.stream().map(b->new CandleEntity("005930",b.timestamp(),b.open(),b.high(),b.low(),b.close(),b.volume())).toList();
            for(var mode:UserStrategyBacktestRequest.ExecutionMode.values()) {
                var result=new UserStrategyBacktestEngine(evaluator).run(new UserStrategyBacktestRequest(definition,"005930",LocalDate.of(2025,1,1),LocalDate.of(2025,1,6),mode),candles);
                assertThat(result.openPosition()).as(condition+mode).isNotNull();
                assertThat(result.openPosition().entry().evidence().get(0).type()).isIn("MACD","BOLLINGER");
            }
            var node=new CompositionDefinition.RuleNode("leaf","source",definition.entry().conditions().get(0),null,null);
            var composition=new CompositionDefinition(null,node,null,null,null,null,null,null,null);
            assertThat(new CompositionEvaluator(composition,null,input).evaluate(node,3).matched()).isTrue();
        }
    }

    StrategyDefinition strategy(String condition) {
        return assertDoesNotThrow(()->json.read("{\"schemaVersion\":2,\"entry\":{\"conditions\":["+condition+"]}}",StrategyDefinition.class),"New indicator JSON must be supported by the actual strategy parser");
    }
    StrategyEvaluator.Evaluation prepare(String condition,int...prices) {return evaluator.prepare(strategy(condition),bars(prices));}
    List<StrategyBar> bars(int...prices) {
        var result=new ArrayList<StrategyBar>();
        for(int i=0;i<prices.length;i++){var p=BigDecimal.valueOf(prices[i]);result.add(new StrategyBar(LocalDate.of(2025,1,1).plusDays(i).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),p,p,BigDecimal.TEN,p,p));}
        return result;
    }
}
