package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.*;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:composite;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types={PaperTradingScheduler.class,ScalpingScheduler.class,CandleCollectionScheduler.class})
@Transactional
class CompositeApiTests {
    @Autowired MockMvc mvc; @Autowired StrategyJson json; @Autowired SavedStrategyService strategies; @Autowired CandleRepository candles;
    @MockitoBean CodexClient codex;
    final CompositeEngineTests f=new CompositeEngineTests();
    StrategyDefinition valid() {return f.strategy(f.leaf("entry",2,Comparison.GT),null,null,null,1,Rebalance.ENTRY_ONLY);}
    @Test void savesAndRunsOneSymbolWithoutUniverseThenHistoryRetainsEverySnapshotAndCascades() throws Exception {
        var created=mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(json.write(valid())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long strategyId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("id").asLong();
        candles.saveAll(f.bars("A"));candles.flush();
        var response=mvc.perform(post("/api/strategies/"+strategyId+"/composite-backtests").contentType(MediaType.APPLICATION_JSON).content(json.write(f.request(f.start,false,"A",false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.result.account.trades[0].quantity").value(8))
                .andExpect(jsonPath("$.snapshot.result.evaluations[0].nodes[0].sourceId").value("source"))
                .andExpect(jsonPath("$.snapshot.strategy.strategy.composition.sources[0].definition.schemaVersion").value(2))
                .andReturn().getResponse().getContentAsString();
        long runId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("id").asLong();
        candles.deleteAll();candles.flush();strategies.update(strategyId,new SavedStrategyService.UpdateRequest(1,valid()));
        assertThat(mvc.perform(get("/api/composite/runs/"+runId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(response);
        mvc.perform(get("/api/strategies/"+strategyId+"/composite-backtests")).andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(1));
        strategies.delete(strategyId);
        mvc.perform(get("/api/composite/runs/"+runId)).andExpect(status().isNotFound());
        verifyNoInteractions(codex);
    }
    @Test void invalidExecutionIsRejectedAndOldSingleSymbolRouteCannotRunV4() throws Exception {
        var saved=strategies.create(valid());
        mvc.perform(post("/api/strategies/"+saved.id()+"/composite-backtests").contentType(MediaType.APPLICATION_JSON).content(json.write(f.request(f.start,false,"A",false)).replace("\"commissionRate\":0","\"commissionRate\":null")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThatThrownBy(()->new UserStrategyBacktestRequest(valid(),"A",f.start,f.start,UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE).requireValid()).isInstanceOf(StrategyValidationException.class);
        mvc.perform(get("/api/strategies/"+saved.id()+"/composite-backtests")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void composeRequiresTwoIndividualSourcesAndCallsNoModel() throws Exception {
        var source=valid().composition().sources().get(0).definition();
        var item=new StrategyBatchService.Item("원문","원문",new StrategyAssistantService.Draft(source,List.of(),List.of(),List.of(),true));
        mvc.perform(post("/api/strategy-assistant/compose").header("X-Strategy-Local","1").contentType(MediaType.APPLICATION_JSON).content(json.write(new StrategyComposer.Request(List.of(item,item),"두 전략"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy.schemaVersion").value(4)).andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.strategy.composition.sources.length()").value(2));
        mvc.perform(post("/api/strategy-assistant/compose").header("X-Strategy-Local","1").contentType(MediaType.APPLICATION_JSON).content(json.write(new StrategyComposer.Request(List.of(item),null))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(codex);
    }
}
