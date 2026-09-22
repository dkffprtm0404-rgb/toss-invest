package com.tossinvest.tossinvestbackend.comparison;

import com.tossinvest.tossinvestbackend.backtest.CandleCollectionScheduler;
import com.tossinvest.tossinvestbackend.backtest.CandleEntity;
import com.tossinvest.tossinvestbackend.backtest.CandleRepository;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.strategy.SavedStrategyService;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real HTTP, browser, H2, and comparison engines, with no model or market calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.datasource.url=jdbc:h2:mem:comparison-web;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@EnabledIfEnvironmentVariable(named = "RUN_WEB_SMOKE", matches = "true")
class ComparisonWebWorkflowTests {
    @LocalServerPort int port;
    @Autowired CandleRepository candles;
    @Autowired SavedStrategyService strategies;
    @Autowired ComparisonRepository comparisons;
    @Autowired StrategyJson json;
    @MockitoBean CodexClient codex;

    @Test
    void browserAddsStoredVersionsComparesRealResultsAndReloadsSavedSnapshot() throws Exception {
        when(codex.status()).thenReturn(new CodexClient.Status(true, "READY", "통합 검증 준비 완료", "test"));
        long firstId = strategies.create(json.read(ComparisonApiTests.SINGLE.replace("손절 비교", "통합 손절 5%"),
                StrategyDefinition.class)).id();
        long secondId = strategies.create(json.read(ComparisonApiTests.SINGLE.replace("손절 비교", "통합 손절 20%")
                .replace("-0.05", "-0.20"), StrategyDefinition.class)).id();
        int[] prices = {100, 100, 94, 93};
        for (int i = 0; i < prices.length; i++) {
            var price = BigDecimal.valueOf(prices[i]);
            long timestamp = LocalDate.of(2025, 1, i + 1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                    .toInstant().toEpochMilli();
            candles.save(new CandleEntity("005930", timestamp, price, price, price, price, BigDecimal.valueOf(100)));
        }
        candles.flush();
        var log = Path.of("build/strategy-comparison-web.log");
        var builder = new ProcessBuilder("node", "--test", "scripts/tests/strategy-comparison.server.cjs")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SW_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("SC_FIRST_ID", Long.toString(firstId));
        builder.environment().put("SC_SECOND_ID", Long.toString(secondId));
        var browser = builder.start();
        try {
            assertThat(browser.waitFor(120, TimeUnit.SECONDS)).withFailMessage("Browser timeout; log: %s", log).isTrue();
            assertThat(browser.exitValue()).withFailMessage(Files.readString(log)).isZero();
            assertThat(comparisons.count()).isEqualTo(1);
            assertThat(candles.count()).isEqualTo(4);
            verify(codex, never()).interpret(anyString());
            verify(codex, never()).interpretBatch(anyString());
            verify(codex, never()).explain(anyString());
        } finally {
            browser.destroyForcibly();
        }
    }
}
