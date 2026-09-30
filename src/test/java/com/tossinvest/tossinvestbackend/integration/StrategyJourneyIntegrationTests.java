package com.tossinvest.tossinvestbackend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tossinvest.tossinvestbackend.backtest.CandleCollectionScheduler;
import com.tossinvest.tossinvestbackend.backtest.CandleEntity;
import com.tossinvest.tossinvestbackend.backtest.CandleRepository;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.stockinfo.StockInfoService;
import com.tossinvest.tossinvestbackend.stockinfo.StockResponse;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** One saved version crosses all feature boundaries; only external providers are simulated. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:strategy-journey;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "strategy.paper.scheduling-enabled=false"})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
class StrategyJourneyIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CandleRepository candles;
    @MockitoBean CodexClient codex;
    @MockitoBean StockInfoService stocks;
    @MockitoBean MarketDataService market;

    @Test
    void interpretedVersionKeepsItsEvidenceThroughBacktestComparisonAndPaperLifecycle() throws Exception {
        var fixture = mapper.readTree(resource("strategy-a.json"));
        var modelOutput = mapper.createObjectNode();
        modelOutput.set("strategy", fixture);
        modelOutput.putArray("questions");
        modelOutput.putArray("unsupported");
        when(codex.interpret(anyString())).thenReturn(modelOutput.toString());
        var bars = fixtureCandles();
        candles.saveAllAndFlush(bars);

        var prompt = mapper.createObjectNode().put("prompt", fixture.path("originalPrompt").asText());
        var draft = response(mvc.perform(post("/api/strategy-assistant/interpret")
                .header("X-Strategy-Local", "1").contentType("application/json").content(prompt.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true)));
        var definition = draft.path("strategy");
        assertThat(definition.path("originalPrompt")).isEqualTo(fixture.path("originalPrompt"));
        mvc.perform(post("/api/strategy-assistant/validate").header("X-Strategy-Local", "1")
                .contentType("application/json").content(definition.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        var saved = create("/api/strategies", definition);
        long strategyId = saved.path("id").asLong();
        assertThat(saved.path("version").asInt()).isEqualTo(1);

        var execution = (ObjectNode) mapper.readTree("""
                {"version":1,"symbol":"005930","startDate":"2025-01-02","endDate":"2025-01-04",
                 "executionMode":"SAME_DAY_CLOSE"}
                """);
        var run = create("/api/strategies/" + strategyId + "/backtests", execution);
        long runId = run.path("id").asLong();
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.at("/snapshot/strategy/id").asLong()).isEqualTo(strategyId);
        assertThat(run.at("/snapshot/strategy/version").asInt()).isEqualTo(1);
        assertThat(run.at("/snapshot/strategy/strategy")).isEqualTo(saved.path("strategy"));
        assertThat(run.at("/snapshot/result/trades")).hasSize(1);
        assertThat(run.at("/snapshot/result/metrics/sumTradeReturnRate").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(run.at("/snapshot/result/openPosition").isObject()).isTrue();

        var analysis = read("/api/backtest/runs/" + runId + "/analysis");
        assertThat(analysis.path("prices")).hasSize(3);
        assertThat(analysis.path("trades")).hasSize(1);
        assertThat(analysis.at("/trades/0/trade/exit/reason").asText()).isEqualTo("STOP_LOSS");
        assertThat(analysis.at("/metrics/sumTradeReturnRate").decimalValue()).isEqualByComparingTo("-0.06");
        when(codex.explain(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT"));
        mvc.perform(post("/api/backtest/runs/" + runId + "/explanation").header("X-Strategy-Local", "1"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("CODEX_TIMEOUT"));
        assertThat(read("/api/backtest/runs/" + runId).path("snapshot")).isEqualTo(run.path("snapshot"));
        assertThat(read("/api/backtest/runs/" + runId + "/analysis")).isEqualTo(analysis);

        long otherId = create("/api/strategies", mapper.readTree(resource("strategy-b.json"))).path("id").asLong();
        var comparisonRequest = execution.deepCopy();
        comparisonRequest.remove("version");
        var selections = comparisonRequest.putArray("strategies");
        selections.addObject().put("id", strategyId).put("version", 1);
        selections.addObject().put("id", otherId).put("version", 1);
        var comparison = create("/api/comparisons", comparisonRequest);
        assertThat(comparison.at("/snapshot/data/sha256")).isEqualTo(run.at("/snapshot/data/sha256"));
        assertThat(comparison.at("/snapshot/entries/0/strategy")).isEqualTo(run.at("/snapshot/strategy"));
        assertThat(comparison.at("/snapshot/entries/0/metrics/returnRate").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(comparison.at("/snapshot/entries/0/metrics/tradeCount").asInt()).isEqualTo(1);
        assertThat(comparison.at("/snapshot/entries/1/metrics/tradeCount").asInt()).isZero();
        assertThat(read("/api/comparisons/" + comparison.path("id").asLong()).path("snapshot"))
                .isEqualTo(comparison.path("snapshot"));

        when(stocks.getStocks("005930")).thenReturn(mapper.readValue("""
                {"result":[{"symbol":"005930","market":"KOSPI","currency":"KRW","securityType":"STOCK"}]}
                """, StockResponse.class));
        when(market.getCandles("005930", "1d", 200)).thenReturn(marketResponse(bars));
        var paperRequest = (ObjectNode) mapper.readTree("""
                {"version":1,"symbol":"005930","executionMode":"SAME_DAY_CLOSE","initialCapital":100000,
                 "allocationRate":1,"commissionRate":0,"taxRate":0,"slippageRate":0,"requestId":"stage9-journey-start"}
                """);
        paperRequest.put("strategyId", strategyId);
        var paper = create("/api/strategy-paper/runs", paperRequest);
        long paperId = paper.path("id").asLong();
        assertThat(paper.path("strategy")).isEqualTo(run.at("/snapshot/strategy"));
        assertThat(paper.path("status").asText()).isEqualTo("RUNNING");
        assertThat(paper.at("/state/cash").decimalValue()).isEqualByComparingTo("100000");
        assertThat(create("/api/strategy-paper/runs", paperRequest).path("id").asLong()).isEqualTo(paperId);
        mvc.perform(post("/api/strategy-paper/runs").contentType("application/json")
                .content(paperRequest.deepCopy().put("requestId", "stage9-journey-duplicate").toString()))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/strategy-paper/runs/" + paperId + "/refresh"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state.dataStatus").value("WAITING_DATA"))
                .andExpect(jsonPath("$.storedBarCount").value(bars.size()))
                .andExpect(jsonPath("$.tradeCount").value(0)).andExpect(jsonPath("$.state.cash").value(100000));
        mvc.perform(post("/api/strategy-paper/runs/" + paperId + "/stop"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
        var reloaded = read("/api/strategy-paper/runs/" + paperId);
        assertThat(reloaded.path("status").asText()).isEqualTo("STOPPED");
        assertThat(reloaded.path("strategy")).isEqualTo(run.at("/snapshot/strategy"));
        assertThat(read("/api/strategy-paper/runs")).hasSize(1);
        assertThat(read("/api/strategy-paper/runs/" + paperId + "/trades")).isEmpty();
        assertThat(candles.count()).isEqualTo(bars.size());
        verify(codex, times(1)).interpret(anyString());
        verify(codex, times(1)).explain(anyString());
        verifyNoMoreInteractions(codex);
    }

    private String resource(String name) throws Exception {
        try (var input = new ClassPathResource("demo/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<CandleEntity> fixtureCandles() throws Exception {
        var lines = resource("candles.csv").lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines.get(0)).isEqualTo("symbol,date,open,high,low,close,volume");
        var result = new ArrayList<CandleEntity>();
        for (var line : lines.subList(1, lines.size())) {
            var values = line.split(",", -1);
            assertThat(values).hasSize(7);
            long timestamp = LocalDate.parse(values[1]).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                    .toInstant().toEpochMilli();
            result.add(new CandleEntity(values[0], timestamp, new BigDecimal(values[2]), new BigDecimal(values[3]),
                    new BigDecimal(values[4]), new BigDecimal(values[5]), new BigDecimal(values[6])));
        }
        return result;
    }

    private CandleResponse marketResponse(List<CandleEntity> bars) throws Exception {
        var body = mapper.createObjectNode();
        var input = body.putObject("result").putArray("candles");
        for (var bar : bars) input.addObject().put("timestamp", bar.getTimestamp().toString()).put("currency", "KRW")
                .put("openPrice", bar.getOpenPrice().toPlainString()).put("highPrice", bar.getHighPrice().toPlainString())
                .put("lowPrice", bar.getLowPrice().toPlainString()).put("closePrice", bar.getClosePrice().toPlainString())
                .put("volume", bar.getVolume().toPlainString());
        return mapper.treeToValue(body, CandleResponse.class);
    }

    private JsonNode create(String path, JsonNode body) throws Exception {
        return response(mvc.perform(post(path).contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()));
    }

    private JsonNode read(String path) throws Exception {
        return response(mvc.perform(get(path)).andExpect(status().isOk()));
    }

    private JsonNode response(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
