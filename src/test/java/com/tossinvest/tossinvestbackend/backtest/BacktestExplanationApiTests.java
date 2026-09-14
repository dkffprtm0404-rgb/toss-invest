package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexProcessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:explanation;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false", "strategy.codex.timeout-millis=1000"})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@Transactional
class BacktestExplanationApiTests {
    static final String OUTPUT = """
            {"highlights":[{"factId":"reason:STOP_LOSS","value":-0.06,"tradeNumbers":[1]}]}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired StrategyJson json;
    @Autowired SavedStrategyService strategies;
    @Autowired SavedBacktestService backtests;
    @Autowired CandleRepository candles;
    @MockitoBean CodexProcessFactory processes;

    @Test void generatesVerifiedEvidenceOnceAndPreservesSnapshotAndOtherRunsWhenDeleted() throws Exception {
        var run = run(true);
        var process = new FakeProcess(events(OUTPUT));
        when(processes.start(any())).thenReturn(process).thenThrow(new IOException("No second model call allowed"));
        var original = backtests.get(run.id());
        String url = "/api/backtest/runs/" + run.id() + "/explanation";
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_GENERATED"));
        String saved = mvc.perform(post(url).header("X-Strategy-Local", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.highlights[0].id").value("reason:STOP_LOSS"))
                .andExpect(jsonPath("$.highlights[0].value").value(-0.06))
                .andExpect(jsonPath("$.highlights[0].tradeNumbers[0]").value(1))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty()).andExpect(jsonPath("$.model").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = mapper.readTree(saved);
        assertThat(body.at("/highlights/0/text").asText()).contains("손절", "-6.00%", "1건");
        assertThat(body.path("dataSha256").asText()).isEqualTo(run.snapshot().data().sha256());
        assertThat(mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEqualTo(body);
        assertThat(mapper.readTree(mvc.perform(post(url).header("X-Strategy-Local", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())).isEqualTo(body);
        assertThat(backtests.get(run.id())).isEqualTo(original);
        assertThat(process.requests()).contains("highlights", "factId", "reason:STOP_LOSS", "outputSchema");
        assertThat(process.requests()).doesNotContain("originalPrompt");
        assertThat(process.closed).isTrue();
        var second = backtests.run(run.snapshot().strategy().id(), json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
        mvc.perform(delete("/api/backtest/runs/{id}", run.id())).andExpect(status().isNoContent());
        mvc.perform(get(url)).andExpect(status().isNotFound());
        assertThat(backtests.get(second.id()).id()).isEqualTo(second.id());
    }

    @Test void rejectsInventedNumbersReferencesProseAndMalformedOutputWithoutLosingResults() throws Exception {
        var run = run(true);
        String url = "/api/backtest/runs/" + run.id() + "/explanation";
        for (String invalid : new String[]{OUTPUT.replace("-0.06", "-0.99"), OUTPUT.replace("[1]", "[2]"),
                OUTPUT.replace("reason:STOP_LOSS", "reason:NEWS"), OUTPUT.replace("[1]", "[1.2]"),
                OUTPUT.replace("[1]", "[\"1\"]"), OUTPUT.replace("-0.06", "\"-0.06\""),
                OUTPUT.replace("\"value\"", "\"text\":\"뉴스 때문에 손실\",\"value\""),
                "{\"highlights\":[]}", "{\"highlights\":null}", "not json"}) {
            when(processes.start(any())).thenReturn(new FakeProcess(events(invalid)));
            mvc.perform(post(url).header("X-Strategy-Local", "1")).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("CODEX_INVALID_RESPONSE"));
            mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_GENERATED"));
            mvc.perform(get("/api/backtest/runs/{id}", run.id())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.snapshot.result.metrics.sumTradeReturnRate").value(-0.06));
        }
    }

    @Test void subscriptionFailuresAndLocalRequestPolicyDoNotAffectNumericalReads() throws Exception {
        var run = run(true);
        String url = "/api/backtest/runs/" + run.id() + "/explanation";
        when(processes.start(any())).thenThrow(new IOException("secret provider path"));
        mvc.perform(post(url).header("X-Strategy-Local", "1")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CODEX_NOT_AVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        mvc.perform(post(url)).andExpect(status().isForbidden());
        mvc.perform(post(url).header("X-Strategy-Local", "1").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("X-Strategy-Local", "1").with(req -> { req.setRemoteAddr("192.0.2.1"); return req; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/backtest/runs/{id}/analysis", run.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.trades.length()").value(1));
    }

    @Test void noDataDoesNotCallModelAndStrategyDeletionCascadesSavedExplanation() throws Exception {
        var empty = run(false);
        when(processes.start(any())).thenThrow(new IOException("Must not generate without closed trades"));
        mvc.perform(post("/api/backtest/runs/{id}/explanation", empty.id()).header("X-Strategy-Local", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_APPLICABLE"));
        var filled = run(true);
        doReturn(new FakeProcess(events(OUTPUT))).when(processes).start(any());
        mvc.perform(post("/api/backtest/runs/{id}/explanation", filled.id()).header("X-Strategy-Local", "1"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/strategies/{id}", filled.snapshot().strategy().id())).andExpect(status().isNoContent());
        mvc.perform(get("/api/backtest/runs/{id}/explanation", filled.id())).andExpect(status().isNotFound());
    }

    private SavedBacktestService.RunDetail run(boolean seed) throws Exception {
        var strategy = strategies.create(json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class));
        if (seed) candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
        return backtests.run(strategy.id(), json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
    }

    private String events(String output) throws Exception {
        return "{\"id\":1,\"result\":{}}\n{\"id\":2,\"result\":{\"account\":{\"type\":\"chatgpt\"}}}\n"
                + "{\"id\":3,\"result\":{\"config\":{}}}\n{\"id\":4,\"result\":{\"thread\":{\"id\":\"th1\"}}}\n"
                + "{\"id\":5,\"result\":{\"account\":{\"type\":\"chatgpt\"}}}\n{\"id\":6,\"result\":{\"turn\":{\"id\":\"t1\"}}}\n"
                + "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"th1\",\"turnId\":\"t1\",\"item\":{\"type\":\"agentMessage\",\"text\":"
                + mapper.writeValueAsString(output) + "}}}\n"
                + "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"th1\",\"turn\":{\"id\":\"t1\",\"status\":\"completed\"}}}\n";
    }
    private static class FakeProcess extends Process {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;
        FakeProcess(String events) { input = new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)); }
        String requests() { return output.toString(StandardCharsets.UTF_8); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { closed = true; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
