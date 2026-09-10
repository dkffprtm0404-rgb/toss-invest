package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:strategy-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class})
@Transactional
class StrategyPersistenceApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CandleRepository candles;

    static final String STRATEGY = """
            {"schemaVersion":1,"name":"거래량 전략","originalPrompt":"거래량에 매수, 5% 손절",
             "entry":{"conditions":[{"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},
             "risk":{"stopLoss":{"rate":-0.05}}}
            """;
    static final String EXECUTION = """
            {"version":1,"symbol":"005930","startDate":"2025-01-02","endDate":"2025-01-04",
             "executionMode":"SAME_DAY_CLOSE"}
            """;

    @BeforeEach
    void seed() { candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93)); }

    @Test
    void savesMetadataAndAppendsVersionsWithoutChangingThePreviousDefinition() throws Exception {
        long id = create();
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"strategy\":" + STRATEGY.replace("-0.05", "-0.10") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/strategies/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy.risk.stopLoss.rate").value(-0.10));
        mvc.perform(get("/api/strategies/" + id + "/versions/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy.name").value("거래량 전략"))
                .andExpect(jsonPath("$.strategy.originalPrompt").value("거래량에 매수, 5% 손절"))
                .andExpect(jsonPath("$.strategy.risk.stopLoss.rate").value(-0.05));
        mvc.perform(get("/api/strategies/" + id + "/versions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
        mvc.perform(get("/api/strategies"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void runPreservesStrategyCandlesCostsAndResultAcrossSubsequentEdits() throws Exception {
        long id = create();
        JsonNode run = run(id, EXECUTION);
        long runId = run.path("id").asLong();
        assertThat(run.at("/snapshot/result/trades/0/exit/reason").asText()).isEqualTo("STOP_LOSS");
        assertThat(run.at("/snapshot/data/candles").size()).isEqualTo(4);
        assertThat(run.at("/snapshot/data/sha256").asText()).hasSize(64);
        assertThat(run.at("/snapshot/costs/model").asText()).isEqualTo("NOT_MODELED");
        assertThat(run.at("/snapshot/costs/commissionRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(run.at("/snapshot/strategy/version").asInt()).isEqualTo(1);
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"strategy\":" + STRATEGY.replace("-0.05", "-0.10") + "}"))
                .andExpect(status().isOk());
        candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 100, 100));
        JsonNode retrieved = read("/api/backtest/runs/" + runId);
        assertThat(retrieved.path("snapshot")).isEqualTo(run.path("snapshot"));
        JsonNode second = run(id, EXECUTION.replace("\"version\":1", "\"version\":2"));
        assertThat(second.at("/snapshot/result/metrics/closedTrades").asInt()).isZero();
        assertThat(second.at("/snapshot/data/sha256").asText()).isNotEqualTo(run.at("/snapshot/data/sha256").asText());
        mvc.perform(get("/api/strategies/" + id + "/backtests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void staleUpdateIsRejectedAndDoesNotCreateAnotherVersion() throws Exception {
        long id = create();
        String update = "{\"expectedVersion\":1,\"strategy\":" + STRATEGY + "}";
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk());
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(read("/api/strategies/" + id + "/versions").size()).isEqualTo(2);
    }

    @Test
    void missingEntitiesReturn404AndInvalidRequestsDoNotCreateHistory() throws Exception {
        mvc.perform(get("/api/strategies/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/backtest/runs/999999")).andExpect(status().isNotFound());
        long id = create();
        mvc.perform(get("/api/strategies/" + id + "/versions/99")).andExpect(status().isNotFound());
        mvc.perform(post("/api/strategies/" + id + "/backtests").contentType(MediaType.APPLICATION_JSON)
                        .content(EXECUTION.replace("\"version\":1", "\"version\":99")))
                .andExpect(status().isNotFound());
        for (String invalid : new String[]{EXECUTION.replace("\"version\":1,", ""),
                EXECUTION.replace("2025-01-02", "2025-01-05"), EXECUTION.replace("SAME_DAY_CLOSE", "BAD")}) {
            mvc.perform(post("/api/strategies/" + id + "/backtests").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(read("/api/strategies/" + id + "/backtests").size()).isZero();
        mvc.perform(get("/api/strategies?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/strategies?size=101")).andExpect(status().isBadRequest());
    }

    @Test
    void strictJsonAndRequiredNameAreValidatedBeforeSaving() throws Exception {
        for (String invalid : new String[]{"null", "{", STRATEGY.replace("stopLoss", "stopLos"),
                STRATEGY.replace("\"period\":1", "\"period\":1.5"), STRATEGY.replace("\"period\":1", "\"period\":\"1\""),
                STRATEGY.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                STRATEGY + " {}", STRATEGY.replace("거래량 전략", " "), STRATEGY.replace("-0.05", "0.05")}) {
            mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(read("/api/strategies").size()).isZero();
    }

    @Test
    void noDataInsufficientDataAndNoTradesAreSavedAsDistinctResults() throws Exception {
        long id = create();
        assertThat(run(id, EXECUTION.replace("005930", "000000")).at("/snapshot/result/status").asText()).isEqualTo("NO_DATA");
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"strategy\":" + STRATEGY.replace("\"period\":1", "\"period\":50") + "}"))
                .andExpect(status().isOk());
        assertThat(run(id, EXECUTION.replace("\"version\":1", "\"version\":2")).at("/snapshot/result/status").asText())
                .isEqualTo("INSUFFICIENT_DATA");
        mvc.perform(put("/api/strategies/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"strategy\":" + STRATEGY.replace("\"multiplier\":1", "\"multiplier\":100") + "}"))
                .andExpect(status().isOk());
        assertThat(run(id, EXECUTION.replace("\"version\":1", "\"version\":3")).at("/snapshot/result/status").asText())
                .isEqualTo("NO_TRADES");
        assertThat(read("/api/strategies/" + id + "/backtests").size()).isEqualTo(3);
    }

    @Test
    void invalidCandleFailureIsSavedWithItsTimestampAndInputSnapshot() throws Exception {
        long id = create();
        var invalid = UserStrategyBacktestEngineTests.candles(100, 100, 94, 93).get(2);
        invalid.setOpenPrice(BigDecimal.ZERO);
        candles.saveAndFlush(invalid);
        JsonNode run = run(id, EXECUTION);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/snapshot/error/code").asText()).isEqualTo("INVALID_DATA");
        assertThat(run.at("/snapshot/error/timestamp").asLong()).isEqualTo(invalid.getTimestamp());
        assertThat(read("/api/backtest/runs/" + run.path("id").asLong()).path("snapshot")).isEqualTo(run.path("snapshot"));
    }

    @Test
    void decimalPrecisionSurvivesStrategyAndResultPersistence() throws Exception {
        String precise = STRATEGY.replace("-0.05", "-0.1234567890123456789");
        String saved = mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(precise))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(saved).path("id").asLong();
        String fetched = mvc.perform(get("/api/strategies/" + id)).andReturn().getResponse().getContentAsString();
        assertThat(fetched).contains("-0.1234567890123456789");
        String run = mvc.perform(post("/api/strategies/" + id + "/backtests").contentType(MediaType.APPLICATION_JSON).content(EXECUTION))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(run).contains("-0.1234567890123456789");
    }

    private long create() throws Exception {
        String json = mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(STRATEGY))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("id").asLong();
    }

    private JsonNode run(long id, String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/strategies/" + id + "/backtests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
