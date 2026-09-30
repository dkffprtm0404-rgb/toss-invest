package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.comparison.ComparisonEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrategyPaperPersistenceTests {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses={PaperRunEntity.class,SavedStrategyEntity.class,CandleEntity.class,ComparisonEntity.class})
    @EnableJpaRepositories(basePackageClasses={PaperRunRepository.class,SavedStrategyRepository.class,CandleRepository.class,com.tossinvest.tossinvestbackend.comparison.ComparisonRepository.class})
    @Import({StrategyPaperService.class,StrategyPaperEngine.class,PaperDeletionGuard.class,SavedStrategyService.class,StrategyValidator.class,StrategyEvaluator.class,StrategyJson.class})
    static class App {
        @Bean PaperMarketData market(){var mock=mock(PaperMarketData.class);when(mock.verifyStock(anyString())).thenReturn("KOSPI");return mock;}
    }
    ConfigurableApplicationContext open(String ddl){return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url=jdbc:h2:file:"+directory.resolve("paper").toAbsolutePath(),"--spring.datasource.username=sa","--spring.datasource.password=",
        "--spring.jpa.hibernate.ddl-auto="+ddl,"--spring.jpa.show-sql=false","--spring.main.banner-mode=off");}
    PaperDefinition request(long id,String key){return new PaperDefinition(id,1,"005930",UserStrategyBacktestRequest.ExecutionMode.NEXT_DAY_OPEN,
        new BigDecimal("1000"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,key);}
    long strategy(ConfigurableApplicationContext c) throws Exception {return c.getBean(SavedStrategyService.class).create(c.getBean(StrategyJson.class).read(StrategyPaperApiTests.STRATEGY,StrategyDefinition.class)).id();}
    static List<StrategyBar> bars(LocalDate first,int...prices){var list=new ArrayList<StrategyBar>();for(int i=0;i<prices.length;i++){var p=BigDecimal.valueOf(prices[i]);list.add(new StrategyBar(first.plusDays(i).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),p,p,BigDecimal.TEN,p,p));}return list;}
    @Test void checkpointSurvivesRestartAndConcurrentRefreshWithoutDuplicateFills() throws Exception {
        long id,strategyId;LocalDate first;
        try(var c=open("create")){
            strategyId=strategy(c);var service=c.getBean(StrategyPaperService.class);var d=service.create(request(strategyId,"restart-request"));id=d.id();first=d.state().firstTradingDate.minusDays(1);
            service.process(id,bars(first,100,100),first.plusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant());
            assertThat(service.get(id).state().pending.side()).isEqualTo("BUY");
            var saved=c.getBean(SavedStrategyService.class);var updated=c.getBean(StrategyJson.class).read(StrategyPaperApiTests.STRATEGY.replace("-0.05","-0.20"),StrategyDefinition.class);
            saved.update(strategyId,new SavedStrategyService.UpdateRequest(1,updated));
        }
        try(var c=open("validate")){
            var service=c.getBean(StrategyPaperService.class);assertThat(service.get(id).strategy().version()).isEqualTo(1);
            var input=bars(first,100,100,100,94,90);var now=first.plusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant();
            var executor=Executors.newFixedThreadPool(2);
            try{var a=executor.submit(()->service.process(id,input,now));var b=executor.submit(()->service.process(id,input,now));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
            var d=service.get(id);assertThat(d.tradeCount()).isEqualTo(2);assertThat(d.state().cash).isEqualByComparingTo("900");
            assertThat(d.state().trades.get(1).reason()).isEqualTo("STOP_LOSS");
            assertThat(d.state().pending.side()).isEqualTo("BUY");service.stop(id);
            c.getBean(SavedStrategyService.class).delete(strategyId);
            assertThat(service.get(id).strategy().strategy().risk().stopLoss().rate()).isEqualByComparingTo("-0.05");
        }
    }
    @Test void failedBatchRollsBackAndDifferentStrategiesHaveSeparateAccounts() throws Exception {
        try(var c=open("create")){
            var service=c.getBean(StrategyPaperService.class);var a=service.create(request(strategy(c),"account-first"));var b=service.create(request(strategy(c),"account-second"));
            var first=a.state().firstTradingDate.minusDays(1);var now=first.plusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant();
            service.process(a.id(),bars(first,100,100),now);
            var invalid=new ArrayList<>(bars(first,100,100,100));var bar=invalid.get(2);invalid.set(2,new StrategyBar(bar.timestamp(),bar.open(),bar.close(),BigDecimal.ZERO,bar.high(),bar.low()));
            assertThatThrownBy(()->service.process(a.id(),invalid,now)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.get(a.id()).tradeCount()).isZero();assertThat(service.get(a.id()).state().pending.side()).isEqualTo("BUY");
            service.process(a.id(),bars(first,100,100,100),now);
            assertThat(service.get(a.id()).state().cash).isEqualByComparingTo("0");assertThat(service.get(b.id()).state().cash).isEqualByComparingTo("1000");
        }
    }
    @Test void refreshDistinguishesProviderFailuresWithoutChangingAccountAndRecovers() throws Exception {
        try(var c=open("create")) {
            var service=c.getBean(StrategyPaperService.class);
            var source=c.getBean(PaperMarketData.class);
            var d=service.create(request(strategy(c),"failure-types"));
            int[] http={400,401,403,429,503};
            String[] codes={"MARKET_REQUEST_REJECTED","MARKET_AUTH_FAILED","MARKET_AUTH_FAILED","MARKET_RATE_LIMITED","MARKET_UNAVAILABLE"};
            for(int i=0;i<http.length;i++) {
                doThrow(
                        org.springframework.web.reactive.function.client.WebClientResponseException.create(http[i],"error",
                                org.springframework.http.HttpHeaders.EMPTY,"secret-provider-body".getBytes(),java.nio.charset.StandardCharsets.UTF_8))
                        .when(source).fetch(anyString(),any(),any());
                var failed=service.refresh(d.id());
                assertThat(failed.state().dataStatus).isEqualTo(codes[i]);
                assertThat(failed.state().cash).isEqualByComparingTo("1000");
                assertThat(failed.tradeCount()).isZero();
                assertThat(failed.state().lastProcessedDate).isNull();
                assertThat(failed.state().logs.get(failed.logCount()-1).message()).contains(String.valueOf(http[i])).doesNotContain("secret-provider-body");
            }
            doReturn(bars(d.state().firstTradingDate.minusDays(2),100)).when(source).fetch(anyString(),any(),any());
            var recovered=service.refresh(d.id());
            assertThat(recovered.state().dataStatus).isEqualTo("WAITING_DATA");
            assertThat(recovered.storedBarCount()).isEqualTo(1);
            assertThat(recovered.state().lastObservedAt).isNotNull();
        }
    }
}
