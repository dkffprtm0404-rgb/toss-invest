package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/UI/H2/engine; only the external model and unrelated schedulers are replaced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.datasource.url=jdbc:h2:mem:web-workflow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@EnabledIfEnvironmentVariable(named = "RUN_WEB_SMOKE", matches = "true")
class StrategyWebWorkflowTests {
    @LocalServerPort int port;
    @Autowired CandleRepository candles;
    @Autowired SavedBacktestRepository runs;
    @MockitoBean CodexClient codex;

    @Test void browserUsesRealValidationPersistenceAndBacktestEndpoints() throws Exception {
        when(codex.status()).thenReturn(new CodexClient.Status(true, "READY", "통합 검증용 모델 응답", "test"));
        when(codex.interpret(anyString())).thenReturn(StrategyAssistantTests.OUTPUT);
        when(codex.model()).thenReturn("test-model");
        when(codex.explain(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT")).thenAnswer(invocation -> {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var facts = mapper.readTree((String) invocation.getArgument(0)).path("facts");
            var selected = mapper.createArrayNode();
            for (var fact : facts) {
                if (fact.path("id").asText().equals("summary:return") || fact.path("id").asText().equals("reason:STOP_LOSS")) {
                    var choice = selected.addObject();
                    choice.put("factId", fact.path("id").asText());
                    choice.set("value", fact.path("value")); choice.set("tradeNumbers", fact.path("tradeNumbers"));
                }
            }
            return mapper.createObjectNode().set("highlights", selected).toString();
        });
        if ("true".equals(System.getenv("RUN_CODEX_LIVE"))) {
            var real = new CodexClient(new CodexProcessFactory("codex"), new com.fasterxml.jackson.databind.ObjectMapper(),
                    "gpt-5.6-terra", 120000);
            when(codex.status()).thenAnswer(invocation -> real.status());
            when(codex.interpret(anyString())).thenAnswer(invocation -> real.interpret(invocation.getArgument(0)));
            when(codex.explain(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT"))
                    .thenAnswer(invocation -> real.explain(invocation.getArgument(0)));
            when(codex.model()).thenReturn(real.model());
        }
        var input = new ArrayList<CandleEntity>();
        for (int i = 0; i < 40; i++) {
            double value = i < 25 ? 100 - i : i < 37 ? 76 + (i - 24) * 3 : 50;
            var price = BigDecimal.valueOf(value);
            long date = LocalDate.of(2025, 1, 1).plusDays(i).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
            input.add(new CandleEntity("005930", date, price, price, price, price, BigDecimal.valueOf(1000)));
        }
        candles.saveAllAndFlush(input);
        var log = Path.of("build/strategy-web-smoke.log");
        var builder = new ProcessBuilder("node", "--test", "scripts/tests/strategy-workbench.server.cjs")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SW_BASE_URL", "http://127.0.0.1:" + port);
        var browser = builder.start();
        try {
            assertThat(browser.waitFor(180, TimeUnit.SECONDS)).isTrue();
            assertThat(browser.exitValue()).withFailMessage(java.nio.file.Files.readString(log)).isZero();
            assertThat(runs.count()).isEqualTo(1);
        } finally { browser.destroyForcibly(); }
    }
}
