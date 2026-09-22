package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Path;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BacktestExplanationPersistenceTests {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {SavedStrategyEntity.class, CandleEntity.class, com.tossinvest.tossinvestbackend.comparison.ComparisonEntity.class})
    @EnableJpaRepositories(basePackageClasses = {SavedStrategyRepository.class, CandleRepository.class, com.tossinvest.tossinvestbackend.comparison.ComparisonRepository.class})
    @Import({SavedStrategyService.class, SavedBacktestService.class, StrategyValidator.class,
            StrategyEvaluator.class, UserStrategyBacktestEngine.class, StrategyJson.class, BacktestExplanationService.class})
    static class Application {
        @Bean CodexClient codex() { return mock(CodexClient.class); }
    }

    @Test void explanationSurvivesRestartAndIsNotRegeneratedAfterStrategyOrCandleChanges() throws Exception {
        long id;
        BacktestExplanationService.Explanation saved;
        try (var context = open("update")) {
            var run = seed(context);
            id = run.id();
            var codex = context.getBean(CodexClient.class);
            when(codex.explain(anyString())).thenReturn(BacktestExplanationApiTests.OUTPUT);
            when(codex.model()).thenReturn("saved-model");
            saved = context.getBean(BacktestExplanationService.class).generate(id);
            var json = context.getBean(StrategyJson.class);
            context.getBean(SavedStrategyService.class).update(run.snapshot().strategy().id(), new SavedStrategyService.UpdateRequest(1,
                    json.read(StrategyPersistenceApiTests.STRATEGY.replace("-0.05", "-0.10"), StrategyDefinition.class)));
            context.getBean(CandleRepository.class).deleteAllInBatch();
        }
        try (var context = open("validate")) {
            when(context.getBean(CodexClient.class).explain(anyString())).thenThrow(new AssistantException("CODEX_NOT_AVAILABLE"));
            var service = context.getBean(BacktestExplanationService.class);
            assertThat(service.get(id)).isEqualTo(saved);
            assertThat(service.generate(id)).isEqualTo(saved);
            assertThat(saved.highlights().get(0).value()).isEqualByComparingTo("-0.06");
            assertThat(saved.model()).isEqualTo("saved-model");
            context.getBean(SavedBacktestService.class).delete(id);
            assertThat(context.getBean(BacktestExplanationRepository.class).count()).isZero();
        }
    }

    @Test void deletionDuringModelCallDoesNotBlockOrResurrectRunAndBusySlotIsReleased() throws Exception {
        try (var context = open("update")) {
            var run = seed(context);
            var service = context.getBean(BacktestExplanationService.class);
            var codex = context.getBean(CodexClient.class);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(codex.model()).thenReturn("test");
            when(codex.explain(anyString())).thenAnswer(invocation -> {
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test model did not finish");
                return BacktestExplanationApiTests.OUTPUT;
            });
            var first = CompletableFuture.supplyAsync(() -> service.generate(run.id()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.generate(run.id())).isInstanceOf(AssistantException.class)
                        .extracting(e -> ((AssistantException) e).code()).isEqualTo("CODEX_BUSY");
                // This completes before the model is released: no generation transaction holds the run lock.
                context.getBean(SavedBacktestService.class).delete(run.id());
            } finally { release.countDown(); }
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SavedStrategyService.NotFoundException.class);
            assertThat(context.getBean(BacktestExplanationRepository.class).count()).isZero();
            var next = context.getBean(SavedBacktestService.class).run(run.snapshot().strategy().id(),
                    context.getBean(StrategyJson.class).read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
            assertThat(service.generate(next.id()).status()).isEqualTo("READY");
            assertThat(service.generate(next.id()).generatedAt()).isEqualTo(service.get(next.id()).generatedAt());
            assertThat(context.getBean(BacktestExplanationRepository.class).count()).isEqualTo(1);
        }
    }

    private SavedBacktestService.RunDetail seed(ConfigurableApplicationContext context) throws Exception {
        var json = context.getBean(StrategyJson.class);
        var strategy = context.getBean(SavedStrategyService.class).create(json.read(StrategyPersistenceApiTests.STRATEGY, StrategyDefinition.class));
        context.getBean(CandleRepository.class).saveAllAndFlush(UserStrategyBacktestEngineTests.candles(100, 100, 94, 93));
        return context.getBean(SavedBacktestService.class).run(strategy.id(), json.read(StrategyPersistenceApiTests.EXECUTION, SavedBacktestService.RunRequest.class));
    }

    private ConfigurableApplicationContext open(String ddl) {
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/'),
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=" + ddl,
                "--spring.jpa.show-sql=false", "--spring.profiles.active=test", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
}
