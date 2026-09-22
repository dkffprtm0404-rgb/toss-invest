package com.tossinvest.tossinvestbackend.comparison;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.composite.CompositeEngine;
import com.tossinvest.tossinvestbackend.portfolio.PortfolioEngine;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComparisonPersistenceTests {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {SavedStrategyEntity.class, CandleEntity.class, ComparisonEntity.class})
    @EnableJpaRepositories(basePackageClasses = {SavedStrategyRepository.class, CandleRepository.class, ComparisonRepository.class})
    @Import({SavedStrategyService.class, StrategyValidator.class, StrategyJson.class, StrategyEvaluator.class,
            UserStrategyBacktestEngine.class, PortfolioEngine.class, CompositeEngine.class, ComparisonService.class})
    static class PersistenceApplication { }

    @Test
    void comparisonSnapshotSurvivesRestartAndStrategyDeletionRemovesItsPersistedMembership() throws Exception {
        long firstId;
        long secondId;
        ComparisonService.Detail original;
        List<ComparisonService.Summary> originalList;
        try (var context = open("update")) {
            var json = context.getBean(StrategyJson.class);
            var strategies = context.getBean(SavedStrategyService.class);
            firstId = strategies.create(json.read(ComparisonApiTests.SINGLE, StrategyDefinition.class)).id();
            secondId = strategies.create(json.read(ComparisonApiTests.SINGLE.replace("손절 비교", "완화 손절")
                    .replace("-0.05", "-0.20"), StrategyDefinition.class)).id();
            var candles = context.getBean(CandleRepository.class);
            int[] prices = {100, 100, 94, 93};
            for (int i = 0; i < prices.length; i++) {
                var price = BigDecimal.valueOf(prices[i]);
                candles.save(new CandleEntity("005930", LocalDate.of(2025, 1, i + 1)
                        .atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),
                        price, price, price, price, BigDecimal.valueOf(100)));
            }
            candles.flush();
            var comparisons = context.getBean(ComparisonService.class);
            original = comparisons.run(new ComparisonService.Request(List.of(
                    new ComparisonService.Selection(firstId, 1), new ComparisonService.Selection(secondId, 1)),
                    "005930", LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 4),
                    UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE, null, null, null, null, null));
            originalList = comparisons.list(0, 20);
            assertThat(original.snapshot().entries().get(0).single().trades()).hasSize(1);
            assertThat(original.snapshot().entries().get(1).single().trades()).isEmpty();
            strategies.update(firstId, new SavedStrategyService.UpdateRequest(1,
                    json.read(ComparisonApiTests.SINGLE.replace("손절 비교", "수정된 전략")
                            .replace("-0.05", "-0.30"), StrategyDefinition.class)));
            candles.deleteAllInBatch();
        }

        assertThat(directory.resolve("comparison.mv.db")).exists();
        try (var context = open("validate")) {
            var strategies = context.getBean(SavedStrategyService.class);
            var comparisons = context.getBean(ComparisonService.class);
            var loaded = comparisons.get(original.id());
            assertThat(loaded).isEqualTo(original);
            assertThat(comparisons.list(0, 20)).isEqualTo(originalList);
            assertThat(loaded.snapshot().data().candles()).hasSize(4);
            var json = context.getBean(StrategyJson.class);
            var capturedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.write(loaded.snapshot().data().candles()).getBytes(StandardCharsets.UTF_8)));
            assertThat(loaded.snapshot().data().sha256()).isEqualTo(capturedHash);
            assertThat(loaded.snapshot().entries()).allSatisfy(entry -> assertThat(entry.strategy().version()).isEqualTo(1));
            assertThat(loaded.snapshot().entries().get(0).strategy().strategy().risk().stopLoss().rate())
                    .isEqualByComparingTo("-0.05");
            assertThat(strategies.get(firstId).version()).isEqualTo(2);
            assertThat(context.getBean(CandleRepository.class).count()).isZero();

            strategies.delete(firstId);
            assertThat(comparisons.list(0, 20)).isEmpty();
            assertThat(context.getBean(ComparisonRepository.class).count()).isZero();
            assertThatThrownBy(() -> comparisons.get(original.id())).isInstanceOf(SavedStrategyService.NotFoundException.class);
            assertThat(strategies.get(secondId).version()).isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext open(String ddl) {
        return new SpringApplicationBuilder(PersistenceApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("comparison").toString().replace('\\', '/'),
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=" + ddl, "--spring.jpa.show-sql=false",
                "--spring.profiles.active=test", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    @Test void concurrentDeletionOfParticipantsDoesNotFailOnSharedComparison() throws Exception {
        try(var context=open("update")) {
            var json=context.getBean(StrategyJson.class);
            var strategies=context.getBean(SavedStrategyService.class);
            var comparisons=context.getBean(ComparisonService.class);
            for(int iteration=0;iteration<4;iteration++) {
                var definition=json.read(ComparisonApiTests.SINGLE,StrategyDefinition.class);
                long a=strategies.create(definition).id(),b=strategies.create(definition).id();
                comparisons.run(new ComparisonService.Request(List.of(new ComparisonService.Selection(a,1),new ComparisonService.Selection(b,1)),
                        "005930",LocalDate.of(2025,1,2),LocalDate.of(2025,1,4),UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE,null,null,null,null,null));
                var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
                var first=CompletableFuture.runAsync(()->deleteTogether(strategies,a,ready,start));
                var second=CompletableFuture.runAsync(()->deleteTogether(strategies,b,ready,start));
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
                first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
                assertThat(comparisons.list(0,20)).isEmpty();
                assertThatThrownBy(()->strategies.get(a)).isInstanceOf(SavedStrategyService.NotFoundException.class);
                assertThatThrownBy(()->strategies.get(b)).isInstanceOf(SavedStrategyService.NotFoundException.class);
            }
        }
    }
    private void deleteTogether(SavedStrategyService service,long id,CountDownLatch ready,CountDownLatch start) {
        try {ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Start timeout");service.delete(id);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
}
