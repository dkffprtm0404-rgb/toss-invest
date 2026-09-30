package com.tossinvest.tossinvestbackend.strategypaper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.paper.PaperTradingScheduler;
import com.tossinvest.tossinvestbackend.scalping.ScalpingScheduler;
import com.tossinvest.tossinvestbackend.backtest.CandleCollectionScheduler;
import com.tossinvest.tossinvestbackend.stockinfo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:strategy-paper-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false","strategy.paper.scheduling-enabled=false"})
@AutoConfigureMockMvc
@MockitoBean(types={PaperTradingScheduler.class,ScalpingScheduler.class,CandleCollectionScheduler.class})
class StrategyPaperApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean StockInfoService stocks;
    static final String STRATEGY="""
        {"schemaVersion":1,"name":"일봉 손절","entry":{"conditions":[{"type":"VOLUME","period":1,"multiplier":1,"comparison":"GTE"}]},"risk":{"stopLoss":{"rate":-0.05}}}
        """;
    @Test void createsIndependentAccountAndRejectsDuplicateActiveRun() throws Exception {
        when(stocks.getStocks("005930")).thenReturn(mapper.readValue("""
            {"result":[{"symbol":"005930","name":"삼성전자","market":"KOSPI","currency":"KRW","securityType":"STOCK","isCommonShare":true}]}
            """,StockResponse.class));
        var saved=mvc.perform(post("/api/strategies").contentType("application/json").content(STRATEGY))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long strategyId=mapper.readTree(saved).get("id").asLong();
        String request=request(strategyId,UUID.randomUUID().toString());
        var created=mvc.perform(post("/api/strategy-paper/runs").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state.cash").value(100000))
                .andExpect(jsonPath("$.status").value("RUNNING")).andReturn().getResponse().getContentAsString();
        long id=mapper.readTree(created).get("id").asLong();
        mvc.perform(post("/api/strategy-paper/runs").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/strategy-paper/runs").contentType("application/json").content(request.replace("100000,","100000.0,").replace("\"allocationRate\":1,","\"allocationRate\":1.0,")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/strategy-paper/runs").contentType("application/json").content(request(strategyId,UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/strategies/"+strategyId)).andExpect(status().isConflict());
        mvc.perform(post("/api/strategy-paper/runs/"+id+"/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(delete("/api/strategies/"+strategyId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/strategy-paper/runs/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy.strategy.name").value("일봉 손절"));
    }
    static String request(long id,String key) {return """
        {"strategyId":%d,"version":1,"symbol":"005930","executionMode":"SAME_DAY_CLOSE","initialCapital":100000,"allocationRate":1,"commissionRate":0,"taxRate":0,"slippageRate":0,"requestId":"%s"}
        """.formatted(id,key);}
}
