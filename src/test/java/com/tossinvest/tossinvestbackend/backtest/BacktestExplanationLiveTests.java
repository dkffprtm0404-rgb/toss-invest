package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional real subscription call with synthetic prices, an isolated H2, and no market schedulers. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:explanation-live;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@EnabledIfEnvironmentVariable(named = "RUN_EXPLANATION_LIVE", matches = "true")
class BacktestExplanationLiveTests {
    @Autowired SavedStrategyService strategies;
    @Autowired SavedBacktestService backtests;
    @Autowired BacktestExplanationService explanations;
    @Autowired StrategyJson json;
    @Autowired CandleRepository candles;

    @Test void subscriptionSelectsRealLossEvidenceAndTheValidatedExplanationIsSaved() throws Exception {
        var strategy = strategies.create(json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class));
        candles.saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
        var run = backtests.run(strategy.id(), json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
        var result = explanations.generate(run.id());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.highlights()).isNotEmpty();
        assertThat(result.highlights()).anyMatch(f -> f.value().compareTo(new java.math.BigDecimal("-0.06")) == 0);
        assertThat(explanations.get(run.id())).isEqualTo(result);
        assertThat(explanations.generate(run.id())).isEqualTo(result);
        Files.writeString(Path.of("build/codex-live-explanation.json"), json.write(result));
    }
}
