package com.tossinvest.tossinvestbackend.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.tossinvest.tossinvestbackend.strategy.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:comparison;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types={PaperTradingScheduler.class, ScalpingScheduler.class, CandleCollectionScheduler.class})
@Transactional
class ComparisonApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CandleRepository candles;

    static final String SINGLE = """
        {"schemaVersion":1,"name":"손절 비교","entry":{"conditions":[{"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},"risk":{"stopLoss":{"rate":-0.05}}}
        """;
    static final String PORTFOLIO = """
        {"schemaVersion":3,"name":"상대강도 비교","portfolio":{"market":"KOSPI","lookbackMonths":1,"topN":1,"smaPeriod":2,"selectionOrder":"FILTER_THEN_RANK","rebalanceTiming":"WEEK_START","weighting":"EQUAL_SLOTS"}}
        """;

    @Test void sameCapturedDataDifferentRulesAndImmutableVersions() throws Exception {
        seed(); long a=create(SINGLE), b=create(SINGLE.replace("-0.05","-0.20"));
        var result=run(request(a,b)); var snapshot=result.path("snapshot");
        assertThat(snapshot.path("type").asText()).isEqualTo("SINGLE");
        assertThat(snapshot.at("/data/candles")).hasSize(4);
        assertThat(snapshot.at("/data/sha256").asText()).hasSize(64);
        assertThat(snapshot.at("/entries/0/metrics/returnRate").decimalValue()).isEqualByComparingTo("-0.06");
        assertThat(snapshot.at("/entries/0/metrics/tradeCount").asInt()).isEqualTo(1);
        assertThat(snapshot.at("/entries/1/metrics/tradeCount").asInt()).isZero();
        assertThat(snapshot.at("/entries/0/single/assumptions").toString()).contains("NO_CAPITAL_QUANTITY_OR_COST_MODEL");
        mvc.perform(put("/api/strategies/"+a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"strategy\":"+SINGLE.replace("-0.05","-0.30")+"}"))
                .andExpect(status().isOk());
        candles.deleteAll(); candles.flush();
        assertThat(read("/api/comparisons/"+result.path("id")).path("snapshot")).isEqualTo(snapshot);
        assertThat(read("/api/comparisons")).hasSize(1);
    }

    @Test void invalidSelectionAndIgnoredCapitalCannotCreateHistory() throws Exception {
        long a=create(SINGLE), b=create(SINGLE), p=create(PORTFOLIO);
        for(ObjectNode body:List.of(request(a,a),request(a,p),request(a,b).put("initialCapital",1000),request(a,b).put("unexpected",1))) {
            mvc.perform(post("/api/comparisons").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isBadRequest());
        }
        var body=request(a,b);body.withArray("strategies").remove(1);
        mvc.perform(post("/api/comparisons").contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        body=request(a,b);((ObjectNode)body.withArray("strategies").get(1)).put("version",99);
        mvc.perform(post("/api/comparisons").contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isNotFound());
        assertThat(read("/api/comparisons")).isEmpty();
    }

    @Test void failuresAndNoDataAreNotPresentedAsSuccessfulZeroReturns() throws Exception {
        long a=create(SINGLE), b=create(SINGLE);
        var empty=run(request(a,b));
        assertThat(empty.at("/snapshot/entries/0/status").asText()).isEqualTo("NO_DATA");
        assertThat(empty.at("/snapshot/entries/0/curve")).isEmpty();
        seed();var bars=candles.findBySymbolOrderByTimestampAsc("005930");bars.get(2).setClosePrice(BigDecimal.ZERO);candles.saveAllAndFlush(bars);
        var failed=run(request(a,b));
        assertThat(failed.at("/snapshot/entries/0/status").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/snapshot/entries/0/metrics").isNull()).isTrue();
        assertThat(failed.at("/snapshot/entries/0/error/code").asText()).isEqualTo("INVALID_DATA");
    }

    @Test void portfolioComparisonRequiresCostsAndUsesAccountMetrics() throws Exception {
        long a=create(PORTFOLIO), b=create(PORTFOLIO.replace("\"topN\":1","\"topN\":2"));
        ObjectNode body=accountRequest(a,b);
        var invalid=body.deepCopy();invalid.remove("commissionRate");
        mvc.perform(post("/api/comparisons").contentType(MediaType.APPLICATION_JSON).content(invalid.toString())).andExpect(status().isBadRequest());
        var result=run(body);
        assertThat(result.at("/snapshot/type").asText()).isEqualTo("PORTFOLIO");
        assertThat(result.at("/snapshot/entries/0/account/initialCapital").decimalValue()).isEqualByComparingTo("1000");
        assertThat(result.at("/snapshot/entries/0/metrics/sharpe").isNull()).isTrue();
        assertThat(result.at("/snapshot/entries/0/metrics/winRate").isNull()).isTrue();
        assertThat(result.at("/snapshot/entries/0/account/trades")).hasSize(1);
        assertThat(result.at("/snapshot/entries/1/account/trades/0/quantity").longValue())
                .isLessThan(result.at("/snapshot/entries/0/account/trades/0/quantity").longValue());
    }

    @Test void comparisonDeletionAndStrategyDeletionRespectOwnership() throws Exception {
        long a=create(SINGLE), b=create(SINGLE), c=create(SINGLE);seed();
        long first=run(request(a,b)).path("id").asLong(), other=run(request(b,c)).path("id").asLong();
        mvc.perform(delete("/api/comparisons/"+first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/strategies/"+a)).andExpect(status().isOk());
        mvc.perform(delete("/api/strategies/"+c)).andExpect(status().isNoContent());
        mvc.perform(get("/api/comparisons/"+other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/comparisons/"+first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/strategies/"+b)).andExpect(status().isOk());
        assertThat(candles.count()).isEqualTo(4);
    }

    @Test void compositeUsesSharedCostsAndPreservesRuleEvidenceWithoutInventedMetrics() throws Exception {
        long a=create(composite(Comparison.GT)),b=create(composite(Comparison.LT));
        var body=accountRequest(a,b);body.remove("universe");body.put("symbol","005930").put("commissionRate",0.01);
        var result=run(body);
        assertThat(result.at("/snapshot/type").asText()).isEqualTo("COMPOSITE");
        assertThat(result.at("/snapshot/entries/0/account/trades")).hasSize(1);
        assertThat(result.at("/snapshot/entries/0/account/trades/0/fee").decimalValue()).isPositive();
        assertThat(result.at("/snapshot/entries/1/account/trades")).isEmpty();
        assertThat(result.at("/snapshot/entries/0/composite/evaluations")).isNotEmpty();
        candles.deleteAll();candles.flush();
        var empty=run(body);
        assertThat(empty.at("/snapshot/entries/0/status").asText()).isEqualTo("NO_DATA");
        assertThat(empty.at("/snapshot/entries/0/metrics").isNull()).isTrue();
        assertThat(empty.at("/snapshot/entries/0/curve")).isEmpty();
    }

    String composite(Comparison comparison) {
        return composite(comparison,2,false);
    }
    String composite(Comparison comparison,int period,boolean selection) {
        var condition=new PriceMovingAverage(AverageType.SMA,period,comparison);
        var original=new StrategyDefinition(2,"source","원문",new ConditionGroup(Operator.AND,List.of(condition)),null,null);
        var source=new Source("source","원문","원문",original,List.of(),List.of(),true,"정책 확인");
        var rank=new RelativeStrength(Market.KOSPI,1,1,2,SelectionOrder.FILTER_THEN_RANK,RebalanceTiming.WEEK_START,Weighting.EQUAL_SLOTS);
        var selectionSource=new Source("selection","선정","선정",new StrategyDefinition(3,"선정","선정",null,null,null,rank),List.of(),List.of(),true,"확인");
        var composition=new CompositionDefinition(selection?List.of(source,selectionSource):List.of(source),new RuleNode("entry","source",condition,null,null),null,
                selection?rank:null,selection?"selection":null,selection?false:null,new Allocation(1,Rebalance.ENTRY_ONLY),true,true);
        return mapper.valueToTree(new StrategyDefinition(4,"조합 "+comparison,"원문",null,null,null,null,composition)).toString();
    }

    @Test void emptyMarketInputAndUnreadyRulesAreNotZeroPerformance() throws Exception {
        for(String definition:List.of(PORTFOLIO,composite(Comparison.GT,2,true))) {
            long a=create(definition),b=create(definition);var body=accountRequest(a,b);
            candles.deleteAll();candles.flush();var result=run(body);
            assertThat(result.at("/snapshot/entries/0/status").asText()).isEqualTo("NO_DATA");
            assertThat(result.at("/snapshot/entries/0/metrics").isNull()).isTrue();
            assertThat(result.at("/snapshot/entries/0/curve")).isEmpty();
        }
        long a=create(composite(Comparison.GT,200,false)),b=create(composite(Comparison.LT,200,false));
        var body=accountRequest(a,b);body.remove("universe");body.put("symbol","005930");
        var result=run(body);
        assertThat(result.at("/snapshot/entries/0/status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.at("/snapshot/entries/0/metrics").isNull()).isTrue();
        assertThat(result.at("/snapshot/entries/0/composite/evaluations")).isNotEmpty();
    }

    @Test void validEscapedNamesPersistWithoutColumnOverflow() throws Exception {
        var body=request(1,2);var selected=body.putArray("strategies");String name="\"\\".repeat(100);
        for(int i=0;i<6;i++) {
            var definition=(ObjectNode)mapper.readTree(SINGLE);definition.put("name",name);
            selected.addObject().put("id",create(definition.toString())).put("version",1);
        }
        run(body);
        assertThat(read("/api/comparisons").get(0).path("strategyNames")).hasSize(6);
        assertThat(read("/api/comparisons").get(0).path("strategyNames").get(0).asText()).isEqualTo(name);
    }

    ObjectNode request(long a,long b) throws Exception {
        return (ObjectNode)mapper.readTree("{\"strategies\":[{\"id\":"+a+",\"version\":1},{\"id\":"+b+",\"version\":1}],\"symbol\":\"005930\",\"startDate\":\"2025-01-02\",\"endDate\":\"2025-01-04\",\"executionMode\":\"SAME_DAY_CLOSE\"}");
    }
    ObjectNode accountRequest(long a,long b) throws Exception {
        var body=request(a,b);body.remove("symbol");body.put("startDate","2025-07-07").put("endDate","2025-07-08")
                .put("initialCapital",1000).put("commissionRate",0).put("taxRate",0).put("slippageRate",0);
        var universe=body.putObject("universe");universe.put("source","historical fixture");
        var dates=universe.putArray("tradingDates");
        LocalDate.of(2025,5,1).datesUntil(LocalDate.of(2025,7,26)).filter(d->d.getDayOfWeek().getValue()<=5).forEach(d->{
            dates.add(d.toString());if(!d.isAfter(LocalDate.of(2025,7,8)))bar(d,100+d.getDayOfYear());
        });
        universe.putArray("members").addObject().put("symbol","005930").put("market","KOSPI").put("from","2025-05-01").putNull("to");
        candles.flush();return body;
    }
    void seed(){int[] p={100,100,94,93};for(int i=0;i<p.length;i++)bar(LocalDate.of(2025,1,i+1),p[i]);candles.flush();}
    void bar(LocalDate day,int price){BigDecimal p=BigDecimal.valueOf(price);candles.save(new CandleEntity("005930",day.atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),p,p,p,p,BigDecimal.valueOf(100)));}
    long create(String definition) throws Exception {return mapper.readTree(mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(definition)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();}
    JsonNode run(ObjectNode body) throws Exception {return mapper.readTree(mvc.perform(post("/api/comparisons").contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
    JsonNode read(String path) throws Exception {return mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
}
