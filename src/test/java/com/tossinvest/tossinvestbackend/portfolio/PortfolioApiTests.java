package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.strategy.*;
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
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:portfolio;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types={PaperTradingScheduler.class,ScalpingScheduler.class,CandleCollectionScheduler.class})
@Transactional
class PortfolioApiTests {
    @Autowired MockMvc mvc; @Autowired StrategyJson json; @Autowired SavedStrategyService strategies; @Autowired CandleRepository candles;
    final PortfolioEngineTests f=new PortfolioEngineTests();

    @Test void savedPortfolioRunsAndHistoryNeverUsesChangedCandlesThenCascadesOnStrategyDelete() throws Exception {
        var saved=strategies.create(f.strategy(1,SelectionOrder.FILTER_THEN_RANK,null)); candles.saveAll(f.bars("A")); candles.flush();
        var response=mvc.perform(post("/api/strategies/"+saved.id()+"/portfolio-backtests").contentType(MediaType.APPLICATION_JSON).content(json.write(f.request(f.start,false,1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.result.finalEquity").value(1000)).andExpect(jsonPath("$.snapshot.result.trades[0].quantity").value(8)).andReturn().getResponse().getContentAsString();
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);long id=tree.path("id").asLong();
        candles.deleteAll(); candles.flush();
        var history=mvc.perform(get("/api/portfolio/runs/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).isEqualTo(response);
        mvc.perform(get("/api/strategies/"+saved.id()+"/portfolio-backtests")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        strategies.delete(saved.id());
        mvc.perform(get("/api/portfolio/runs/"+id)).andExpect(status().isNotFound());
    }
    @Test void missingHeldDataIsSavedAsFailureAndCostsAreMandatory() throws Exception {
        var saved=strategies.create(f.strategy(1,SelectionOrder.FILTER_THEN_RANK,null));var bars=f.bars("A");bars.removeIf(c->f.date(c).equals(f.start.plusDays(1)));candles.saveAll(bars);candles.flush();
        mvc.perform(post("/api/strategies/"+saved.id()+"/portfolio-backtests").contentType(MediaType.APPLICATION_JSON).content(json.write(f.request(f.start.plusDays(1),false,1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.snapshot.error.code").value("INVALID_DATA"));
        String missing=json.write(f.request(f.start,false,1)).replace("\"commissionRate\":0", "\"commissionRate\":null");
        mvc.perform(post("/api/strategies/"+saved.id()+"/portfolio-backtests").contentType(MediaType.APPLICATION_JSON).content(missing)).andExpect(status().isBadRequest());
    }
    @Test void portfolioRequiresExplicitPolicyAndCannotRunInSingleSymbolEngine() {
        var definition=f.strategy(1,SelectionOrder.FILTER_THEN_RANK,null);
        var missing=new StrategyDefinition(3,"test",null,null,null,null,new RelativeStrength(Market.KOSPI,6,10,200,null,null,null));
        assertThat(new StrategyValidator().validate(missing)).extracting(StrategyValidator.Issue::path).contains("portfolio.selectionOrder","portfolio.rebalanceTiming","portfolio.weighting");
        assertThatThrownBy(()->new UserStrategyBacktestRequest(definition,"A",f.start,f.start,UserStrategyBacktestRequest.ExecutionMode.SAME_DAY_CLOSE).requireValid()).isInstanceOf(StrategyValidationException.class);
        var mixed=new StrategyDefinition(3,"mixed",null,new ConditionGroup(null,java.util.List.of(new PriceMovingAverage(AverageType.SMA,200,Comparison.GT))),null,null,definition.portfolio());
        assertThat(new StrategyValidator().validate(mixed)).extracting(StrategyValidator.Issue::path).contains("portfolio");
        var wrongRisk=new StrategyDefinition(3,"risk",null,null,null,new Risk(null,new TakeProfit(java.math.BigDecimal.ONE),null,null),definition.portfolio());
        assertThat(new StrategyValidator().validate(wrongRisk)).extracting(StrategyValidator.Issue::path).contains("risk");
    }
}
