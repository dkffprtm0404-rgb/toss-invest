package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.strategy.SavedStrategyService;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:analysis;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@Transactional
class BacktestAnalysisApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired StrategyJson json;
    @Autowired SavedStrategyService strategies;
    @Autowired SavedBacktestService backtests;
    @Autowired CandleRepository candles;

    @Test void chartsAndGroupsUseSavedClosedTradesEvenAfterSourceDataAndStrategyChange() throws Exception {
        var strategy = strategies.create(json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class));
        candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
        var run = backtests.run(strategy.id(), json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
        candles.deleteAllInBatch();
        strategies.update(strategy.id(), new SavedStrategyService.UpdateRequest(1,
                json.read(StrategyPersistenceApiTests.STRATEGY.replace("-0.05", "-0.10"), StrategyDefinition.class)));
        JsonNode analysis = mapper.readTree(mvc.perform(get("/api/backtest/runs/{id}/analysis", run.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(analysis.path("prices")).hasSize(3); // Warmup Jan 1 is not a performance-period price.
        assertThat(analysis.at("/prices/1/close").decimalValue()).isEqualByComparingTo("94");
        assertThat(analysis.path("curve")).hasSize(2);
        assertThat(analysis.at("/curve/0/sumReturnRate").decimalValue()).isZero();
        assertThat(analysis.at("/curve/1/sumReturnRate").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(analysis.at("/curve/1/drawdown").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(analysis.at("/trades/0/number").asInt()).isEqualTo(1);
        assertThat(analysis.at("/trades/0/trade/exit/reason").asText()).isEqualTo("STOP_LOSS");
        assertThat(analysis.at("/months/0/key").asText()).isEqualTo("2025-01");
        assertThat(analysis.at("/months/0/sumReturnRate").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(analysis.at("/exitReasons/0/key").asText()).isEqualTo("STOP_LOSS");
        assertThat(analysis.at("/exitReasons/0/tradeNumbers/0").asInt()).isEqualTo(1);
        assertThat(analysis.at("/metrics/closedTrades").asInt()).isEqualTo(1);
        // Jan 4's new open position must not become a completed trade/chart point.
        assertThat(run.snapshot().result().openPosition()).isNotNull();
    }

    @Test void noDataAndInsufficientDataStayDistinctWithoutInventedPerformance() throws Exception {
        var strategy = strategies.create(json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class));
        var request = json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class);
        var noData = backtests.run(strategy.id(), request);
        mvc.perform(get("/api/backtest/runs/{id}/analysis", noData.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA")).andExpect(jsonPath("$.curve").isEmpty())
                .andExpect(jsonPath("$.facts").isEmpty());
        candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100).subList(1, 2));
        var insufficient = backtests.run(strategy.id(), request);
        mvc.perform(get("/api/backtest/runs/{id}/analysis", insufficient.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA")).andExpect(jsonPath("$.facts").isEmpty());
        mvc.perform(get("/api/backtest/runs/999999/analysis")).andExpect(status().isNotFound());
    }
}
