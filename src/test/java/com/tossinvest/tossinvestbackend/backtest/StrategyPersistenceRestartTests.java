package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyPersistenceRestartTests {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {SavedStrategyEntity.class, CandleEntity.class, com.tossinvest.tossinvestbackend.comparison.ComparisonEntity.class})
    @EnableJpaRepositories(basePackageClasses = {SavedStrategyRepository.class, CandleRepository.class, com.tossinvest.tossinvestbackend.comparison.ComparisonRepository.class})
    @Import({SavedStrategyService.class, SavedBacktestService.class, StrategyValidator.class,
            StrategyEvaluator.class, UserStrategyBacktestEngine.class, StrategyJson.class})
    static class PersistenceApplication { }

    @Test
    void fileDatabaseSurvivesFullContextShutdownAndReopenWithSchemaValidation() throws Exception {
        long strategyId;
        long runId;
        SavedBacktestService.RunDetail original;
        try (var context = open("update")) {
            var json = context.getBean(StrategyJson.class);
            var strategies = context.getBean(SavedStrategyService.class);
            var definition = json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class);
            strategyId = strategies.create(definition).id();
            context.getBean(CandleRepository.class).saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
            original = context.getBean(SavedBacktestService.class).run(strategyId,
                    json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
            runId = original.id();
            strategies.update(strategyId, new SavedStrategyService.UpdateRequest(1,
                    json.read(StrategyPersistenceApiTests.STRATEGY.replace("-0.05", "-0.10"), StrategyDefinition.class)));
            context.getBean(CandleRepository.class).deleteAllInBatch();
        }
        assertThat(directory.resolve("history.mv.db")).exists();
        try (var context = open("validate")) {
            var strategies = context.getBean(SavedStrategyService.class);
            assertThat(strategies.get(strategyId).version()).isEqualTo(2);
            assertThat(strategies.version(strategyId, 1).strategy().risk().stopLoss().rate()).isEqualByComparingTo("-0.05");
            var loaded = context.getBean(SavedBacktestService.class).get(runId);
            assertThat(loaded.snapshot()).isEqualTo(original.snapshot());
            assertThat(loaded.snapshot().data().candles()).hasSize(4);
            assertThat(context.getBean(CandleRepository.class).count()).isZero();
            var replayCandles = loaded.snapshot().data().candles().stream().map(c -> new CandleEntity(
                    c.symbol(), c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume())).toList();
            assertThat(context.getBean(UserStrategyBacktestEngine.class).run(loaded.snapshot().execution(), replayCandles))
                    .isEqualTo(loaded.snapshot().result());
        }
    }

    @Test
    void competingEditsWithTheSameExpectedVersionProduceOneRevisionAndOneConflict() throws Exception {
        try (var context = open("update")) {
            var strategies = context.getBean(SavedStrategyService.class);
            var definition = context.getBean(ObjectMapper.class).readValue(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class);
            long id = strategies.create(definition).id();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> update(strategies, id, definition, ready, start));
            var second = CompletableFuture.supplyAsync(() -> update(strategies, id, definition, ready, start));
            boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPDATED", "VERSION_CONFLICT");
            assertThat(strategies.versions(id, 0, 20)).hasSize(2);
        }
    }

    private String update(SavedStrategyService service, long id, StrategyDefinition definition,
                          CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            service.update(id, new SavedStrategyService.UpdateRequest(1, definition));
            return "UPDATED";
        } catch (SavedStrategyService.VersionConflictException e) {
            return "VERSION_CONFLICT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ConfigurableApplicationContext open(String ddl) {
        return new SpringApplicationBuilder(PersistenceApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/'),
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=" + ddl, "--spring.jpa.show-sql=false",
                "--spring.profiles.active=test", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
}
