package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.hasItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-strategy-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class})
@Transactional
class UserStrategyBacktestApiTests {
    @Autowired MockMvc mvc;
    @Autowired CandleRepository repository;

    private static final String ENDPOINT = "/api/backtest/run-strategy";
    private static final String REQUEST = """
            {"symbol":"005930","startDate":"2025-01-02","endDate":"2025-01-04","executionMode":"SAME_DAY_CLOSE",
             "strategy":{"schemaVersion":1,"name":"volume and stop","originalPrompt":"buy on volume, stop at 5% loss",
               "entry":{"conditions":[{"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},
               "risk":{"stopLoss":{"rate":-0.05}}}}
            """;

    @BeforeEach
    void seedIsolatedDatabase() {
        repository.saveAll(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
    }

    @Test
    void realJsonAndStoredCandlesProduceTradesAndEchoTheExecutionSnapshot() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.warmupBars").value(1))
                .andExpect(jsonPath("$.trades[0].exit.reason").value("STOP_LOSS"))
                .andExpect(jsonPath("$.trades[0].returnRate").value(-0.06))
                .andExpect(jsonPath("$.trades[0].entry.evidence[0].path").value("entry.conditions[0]"))
                .andExpect(jsonPath("$.execution.strategy.entry.conditions[0].type").value("VOLUME"))
                .andExpect(jsonPath("$.execution.strategy.risk.stopLoss.rate").value(-0.05))
                .andExpect(jsonPath("$.metrics.closedTrades").value(1))
                .andExpect(jsonPath("$.metrics.sumTradeReturnRate").value(-0.06))
                .andExpect(jsonPath("$.openPosition.entry.executionTimestamp").value(UserStrategyBacktestEngineTests.time(3, 15, 30)));
    }

    @Test
    void endDateIsInclusiveInKstAndFutureInvalidCandlesAreNotLoaded() throws Exception {
        var future = UserStrategyBacktestEngineTests.candles(100, 100, 100, 100, 100).get(4);
        future.setOpenPrice(BigDecimal.ZERO);
        repository.save(future);
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("SAME_DAY_CLOSE", "NEXT_DAY_OPEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candleCount").value(3))
                .andExpect(jsonPath("$.actualEndDate").value("2025-01-04"))
                .andExpect(jsonPath("$.openPosition.entry.executionTimestamp").value(UserStrategyBacktestEngineTests.time(2, 9, 0)));
    }

    @Test
    void missingSettingsAndRiskValuesReturnFieldLevelErrors() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("""
                        {"strategy":{"schemaVersion":1,"entry":{"conditions":[{"type":"MA_CROSS","direction":"UP"}]},
                         "risk":{"stopLoss":{}}}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.issues[*].path", hasItem("entry.conditions[0].shortPeriod")))
                .andExpect(jsonPath("$.issues[*].path", hasItem("risk.stopLoss.rate")))
                .andExpect(jsonPath("$.issues[*].path", hasItem("executionMode")));
    }

    @Test
    void unknownFieldsCannotSilentlyRemoveARiskPolicy() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("stopLoss", "stopLos")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedConditionsAndFractionalPeriodsAreRejected() throws Exception {
        for (String body : new String[]{REQUEST.replace("VOLUME", "MACD"), REQUEST.replace("\"period\":1", "\"period\":1.5"),
                REQUEST.replace("\"comparison\":\"GTE\"", "\"comparison\":0")}) {
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void malformedAndNullBodiesReturnJsonErrors() throws Exception {
        for (String body : new String[]{"{", "null"}) {
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void unavailableSymbolIsNoDataAndReversedDatesAreValidationError() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("005930", "000000")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_DATA"));
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("2025-01-02", "2025-01-05")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.issues[*].path", hasItem("endDate")));
    }

    @Test
    void invalidStoredPriceReturns422WithTheCandleTimestamp() throws Exception {
        var invalid = UserStrategyBacktestEngineTests.candles(100, 100, 100).get(2);
        invalid.setOpenPrice(BigDecimal.ZERO);
        repository.saveAndFlush(invalid);
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DATA"))
                .andExpect(jsonPath("$.timestamp").value(UserStrategyBacktestEngineTests.time(2, 0, 0)));
    }

    @Test
    void exactDecimalRiskRateSurvivesTheApiBoundary() throws Exception {
        String content = mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("-0.05", "-0.1234567890123456789")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(content);
        assertThat(json.at("/execution/strategy/risk/stopLoss/rate").decimalValue())
                .isEqualByComparingTo("-0.1234567890123456789");
    }

    @Test
    void emptySelectedPolicyAndStringNumbersAreNotCoercedIntoOtherSettings() throws Exception {
        for (String body : new String[]{REQUEST.replace("\"risk\":{", "\"risk\":{\"trailing\":\"\","),
                REQUEST.replace("\"period\":1", "\"period\":\"1\"")}) {
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void duplicateFieldsAndTrailingJsonAreRejected() throws Exception {
        for (String body : new String[]{REQUEST.replace("\"schemaVersion\":1", "\"schemaVersion\":999,\"schemaVersion\":1"), REQUEST + " {}"}) {
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }
}
