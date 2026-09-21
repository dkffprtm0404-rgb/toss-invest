package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.*;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","spring.datasource.url=jdbc:h2:mem:composite-web;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@MockitoBean(types={PaperTradingScheduler.class,ScalpingScheduler.class,CandleCollectionScheduler.class})
@EnabledIfEnvironmentVariable(named="RUN_WEB_SMOKE",matches="true")
class CompositeWebWorkflowTests {
    @LocalServerPort int port;
    @Autowired CandleRepository candles;
    @Autowired CompositeRunRepository runs;
    @Autowired StrategyJson json;
    @MockitoBean CodexClient codex;
    @Test void realHttpSixDraftsPlusTotalAndSelectedCompositionSaveRunAndHistory() throws Exception {
        when(codex.status()).thenReturn(new CodexClient.Status(true,"READY","통합 검증용 모델 응답","test"));
        String[] texts={"20일 고가 돌파","52주 신고가","SMA 골든크로스 + 장기 필터","상대강도 상위주","ATR 변동성 돌파","50/200일 골든크로스"};
        var stop=new Risk(new StopLoss(new BigDecimal("-0.05")),null,null,null);
        var low=new ConditionGroup(null,List.of(new RangeBreakout(10,PeriodUnit.BARS,PriceField.LOW,Comparison.LT)));
        var definitions=List.of(
            new StrategyDefinition(2,texts[0],null,new ConditionGroup(null,List.of(new RangeBreakout(20,PeriodUnit.BARS,PriceField.HIGH,Comparison.CROSS_ABOVE))),null,new Risk(stop.stopLoss(),null,null,null,new TrailingStop(new BigDecimal("-0.2"),PeakBasis.HIGH),null)),
            new StrategyDefinition(2,texts[1],null,new ConditionGroup(null,List.of(new RangeBreakout(52,PeriodUnit.CALENDAR_WEEKS,PriceField.HIGH,Comparison.GT))),low,stop),
            new StrategyDefinition(2,texts[2],null,new ConditionGroup(Operator.AND,List.of(new MovingAverageCross(AverageType.SMA,5,20,Direction.UP),new MovingAverageCompare(AverageType.SMA,20,200,Comparison.GT))),new ConditionGroup(null,List.of(new MovingAverageCross(AverageType.SMA,5,20,Direction.DOWN))),stop),
            new StrategyDefinition(3,texts[3],null,null,null,new Risk(new StopLoss(new BigDecimal("-0.08")),null,null,null),new RelativeStrength(Market.KOSPI_KOSDAQ,6,10,200,null,null,Weighting.EQUAL_SLOTS)),
            new StrategyDefinition(2,texts[4],null,new ConditionGroup(null,List.of(new AtrBreakout(null,14,new BigDecimal("0.5"),Comparison.CROSS_ABOVE))),low,stop),
            new StrategyDefinition(2,texts[5],null,new ConditionGroup(null,List.of(new MovingAverageCross(AverageType.SMA,50,200,Direction.UP))),new ConditionGroup(null,List.of(new MovingAverageCross(AverageType.SMA,50,200,Direction.DOWN))),new Risk(new StopLoss(null),null,null,null)));
        var outputs=new ArrayList<StrategyBatchService.ItemOutput>();
        for(int i=0;i<texts.length;i++)outputs.add(new StrategyBatchService.ItemOutput(texts[i],texts[i],definitions.get(i),i>=3?List.of("누락된 선택과 정확한 수치를 확인해 주세요."):List.of(),List.of()));
        when(codex.interpretBatch(anyString())).thenReturn(json.write(new StrategyBatchService.Output("",outputs,List.of())));
        var input=new ArrayList<CandleEntity>();
        for(int i=0;i<280;i++) {
            var day=LocalDate.of(2024,8,1).plusDays(i);var price=BigDecimal.valueOf(i<260?100:120+i-260);
            input.add(new CandleEntity("005930",day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),price,price,price,price,BigDecimal.valueOf(1000)));
        }
        candles.saveAllAndFlush(input);
        var log=Path.of("build/strategy-composition-web.log");
        var builder=new ProcessBuilder("node","--test","scripts/tests/strategy-composition.server.cjs").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SW_BASE_URL","http://127.0.0.1:"+port);
        var process=builder.start();
        try {
            assertThat(process.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
            assertThat(runs.count()).isEqualTo(1);
            verify(codex,times(1)).interpretBatch(anyString());verify(codex,never()).interpret(anyString());
            // Resolve the entire six-source draft and execute through real HTTP as well.
            var items=new ArrayList<StrategyBatchService.Item>();
            for(int i=0;i<texts.length;i++)items.add(new StrategyBatchService.Item(texts[i],texts[i],new StrategyAssistantService.Draft(definitions.get(i),List.of(),outputs.get(i).questions(),List.of(),false)));
            var total=new StrategyComposer().compose(new StrategyComposer.Request(items,"검증된 전체 토탈")).strategy();
            var c=total.composition();
            var sources=c.sources().stream().map(s->new CompositionDefinition.Source(s.id(),s.title(),s.prompt(),s.definition(),s.questions(),s.unsupported(),true,"공통 손절 -5%, 진입·청산 OR, ATR SIMPLE 전일 기준, 주 첫 거래일 필터 후 순위 선정으로 확정")).toList();
            var selection=new RelativeStrength(Market.KOSPI_KOSDAQ,6,10,200,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_START,Weighting.EQUAL_SLOTS);
            var complete=new StrategyDefinition(4,total.name(),total.originalPrompt(),null,null,stop,null,new CompositionDefinition(sources,resolve(c.entry()),resolve(c.exit()),selection,c.selectionSourceId(),true,new CompositionDefinition.Allocation(10,CompositionDefinition.Rebalance.ENTRY_ONLY),true,true));
            var client=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+port;
            var saveResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/strategies")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.write(complete))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(saveResponse.statusCode()).withFailMessage(saveResponse.body()).isEqualTo(201);
            var saved=json.read(saveResponse.body(),SavedStrategyService.SavedVersion.class);
            var calendar=LocalDate.of(2024,8,1).datesUntil(LocalDate.of(2025,5,25)).toList();
            var universe=new com.tossinvest.tossinvestbackend.portfolio.PortfolioRequest.Universe("합성 전체 시장",calendar,List.of(new com.tossinvest.tossinvestbackend.portfolio.PortfolioRequest.Member("005930",Market.KOSPI,calendar.get(0),null)));
            var request=new CompositeRequest(1,LocalDate.of(2025,4,17),LocalDate.of(2025,5,7),UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE,BigDecimal.valueOf(1000000),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,universe);
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/strategies/"+saved.id()+"/composite-backtests")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.write(request))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            var detail=json.read(response.body(),CompositeService.Detail.class);
            assertThat(detail.snapshot().error()).isNull();assertThat(detail.snapshot().result().evaluations()).isNotEmpty();
            assertThat(detail.snapshot().strategy().strategy().composition().sources()).hasSize(6);
            var history=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/composite/runs/"+detail.id())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(history.statusCode()).isEqualTo(200);assertThat(history.body()).isEqualTo(response.body());
        }finally{process.destroyForcibly();}
    }
    private CompositionDefinition.RuleNode resolve(CompositionDefinition.RuleNode node) {
        if(node==null)return null;
        var condition=node.condition();
        if(condition instanceof AtrBreakout a)condition=new AtrBreakout(AtrMethod.SIMPLE,a.period(),a.multiplier(),a.comparison());
        return new CompositionDefinition.RuleNode(node.id(),node.sourceId(),condition,node.children()==null?null:node.operator()==null?Operator.OR:node.operator(),node.children()==null?null:node.children().stream().map(this::resolve).toList());
    }
}
