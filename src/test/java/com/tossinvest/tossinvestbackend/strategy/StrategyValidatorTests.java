package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StrategyValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StrategyValidator validator = new StrategyValidator();

    @Test
    void missingValuesAreReportedTogetherWithoutFillingDefaults() throws Exception {
        var strategy = read("""
                {"schemaVersion":1,"entry":{"conditions":[{"type":"MA_CROSS","direction":"UP"}]},
                 "risk":{"stopLoss":{}}}
                """);
        assertThat(validator.validate(strategy)).extracting(StrategyValidator.Issue::path)
                .containsExactly("entry.conditions[0].averageType", "entry.conditions[0].shortPeriod",
                        "entry.conditions[0].longPeriod", "risk.stopLoss.rate");
    }

    @Test
    void entryOnlyStrategyAndSingleConditionWithoutOperatorAreValid() throws Exception {
        assertThat(validator.validate(read("""
                {"schemaVersion":1,"entry":{"conditions":[
                  {"type":"MA_CROSS","averageType":"SMA","shortPeriod":5,"longPeriod":20,"direction":"UP"}]}}
                """))).isEmpty();
    }

    @Test
    void invalidValuesAndMissingGroupOperatorAreRejected() throws Exception {
        var issues = validator.validate(read("""
                {"schemaVersion":99,"entry":{"conditions":[
                 {"type":"MA_CROSS","averageType":"EMA","shortPeriod":20,"longPeriod":5,"direction":"UP"},
                 {"type":"RSI","method":"SIMPLE","period":0,"threshold":101,"comparison":"GTE"},
                 {"type":"VOLUME","period":501,"multiplier":0,"comparison":"CROSS_ABOVE"}]},
                 "risk":{"stopLoss":{"rate":0.05},"takeProfit":{"rate":0},"timeExit":{"days":-1}}}
                """));
        assertThat(issues).extracting(StrategyValidator.Issue::path).contains(
                "schemaVersion", "entry.operator", "entry.conditions[0].longPeriod",
                "entry.conditions[1].period", "entry.conditions[1].threshold", "entry.conditions[2].period",
                "entry.conditions[2].multiplier", "entry.conditions[2].comparison",
                "risk.stopLoss.rate", "risk.takeProfit.rate", "risk.timeExit.days");
    }

    @Test
    void emptyAndNullConditionsCannotBecomeAlwaysTrue() throws Exception {
        assertThat(validator.validate(read("""
                {"schemaVersion":1,"entry":{"operator":"AND","conditions":[]}}
                """))).extracting(StrategyValidator.Issue::path).contains("entry.conditions");
        assertThat(validator.validate(read("""
                {"schemaVersion":1,"entry":{"conditions":[null]}}
                """))).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0]");
    }

    @Test
    void nullStrategyAndExplicitlyEmptyExitAreRejected() throws Exception {
        assertThat(validator.validate(null)).extracting(StrategyValidator.Issue::path).containsExactly("strategy");
        assertThat(validator.validate(read("""
                {"schemaVersion":1,"entry":{"conditions":[
                 {"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},"exit":{}}
                """))).extracting(StrategyValidator.Issue::path).contains("exit.conditions");
    }

    @Test void rsiRejectsComparisonsThatCannotBeDisplayedOrGenerated() throws Exception {
        for (String comparison : List.of("GT", "LT")) {
            var issues = validator.validate(read("{\"schemaVersion\":2,\"entry\":{\"conditions\":["
                    + "{\"type\":\"RSI\",\"method\":\"SIMPLE\",\"period\":14,\"threshold\":30,\"comparison\":\"" + comparison + "\"}]}}"));
            assertThat(issues).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0].comparison");
        }
    }

    private StrategyDefinition read(String json) throws Exception {
        return mapper.readValue(json, StrategyDefinition.class);
    }
}
