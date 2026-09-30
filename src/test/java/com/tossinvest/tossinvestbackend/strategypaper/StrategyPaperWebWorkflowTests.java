package com.tossinvest.tossinvestbackend.strategypaper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.backtest.CandleCollectionScheduler;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.stockinfo.*;
import com.tossinvest.tossinvestbackend.marketdata.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","spring.datasource.url=jdbc:h2:mem:paper-web;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false","strategy.paper.scheduling-enabled=false"})
@MockitoBean(types={PaperTradingScheduler.class,ScalpingScheduler.class,CandleCollectionScheduler.class})
@EnabledIfEnvironmentVariable(named="RUN_WEB_SMOKE",matches="true")
class StrategyPaperWebWorkflowTests {
    @LocalServerPort int port;
    @Autowired SavedStrategyService strategies;
    @Autowired StrategyJson json;
    @Autowired ObjectMapper mapper;
    @Autowired PaperRunRepository runs;
    @MockitoBean StockInfoService stocks;
    @MockitoBean MarketDataService market;
    @MockitoBean CodexClient codex;
    @Test void savedVersionStartsRefreshesStopsAndReloadsThroughRealHttp() throws Exception {
        when(codex.status()).thenReturn(new CodexClient.Status(true,"READY","테스트","test"));
        when(stocks.getStocks("005930")).thenReturn(mapper.readValue("""
            {"result":[{"symbol":"005930","market":"KOSPI","currency":"KRW","securityType":"STOCK"}]}
            """,StockResponse.class));
        String day=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        when(market.getCandles("005930","1d",200)).thenReturn(mapper.readValue("""
            {"result":{"candles":[{"timestamp":"%sT00:00:00+09:00","currency":"KRW","openPrice":"100","highPrice":"100","lowPrice":"100","closePrice":"100","volume":"10"}]}}
            """.formatted(day),CandleResponse.class));
        long id=strategies.create(json.read(StrategyPaperApiTests.STRATEGY,StrategyDefinition.class)).id();
        Path log=Path.of("build/strategy-paper-web.log");
        var builder=new ProcessBuilder("node","--test","scripts/tests/strategy-paper.server.cjs").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SW_BASE_URL","http://127.0.0.1:"+port);builder.environment().put("SP_STRATEGY_ID",Long.toString(id));
        var process=builder.start();
        try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();}
        finally{process.destroyForcibly();}
        assertThat(runs.count()).isEqualTo(1);assertThat(runs.findAll().get(0).getStatus()).isEqualTo("STOPPED");
        verify(codex,never()).interpret(anyString());verify(codex,never()).interpretBatch(anyString());verify(codex,never()).explain(anyString());
    }
}
