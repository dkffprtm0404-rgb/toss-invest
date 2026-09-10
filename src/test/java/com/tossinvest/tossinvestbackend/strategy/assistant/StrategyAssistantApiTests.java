package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:assistant;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class})
@Transactional
class StrategyAssistantApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CodexClient codex;

    @Test void interpretedStrategyCanBeConfirmedSavedRunAndRetrievedWithoutAnotherLlmCall() throws Exception {
        when(codex.interpret(anyString())).thenReturn(StrategyAssistantTests.OUTPUT);
        var draft = mapper.readTree(mvc.perform(post("/api/strategy-assistant/interpret")
                .header("X-Strategy-Local", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"SMA 5일 20일 골든크로스 매수, 5% 손절\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
                .andReturn().getResponse().getContentAsString());
        String strategy = mapper.writeValueAsString(draft.get("strategy"));
        mvc.perform(post("/api/strategy-assistant/validate").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content(strategy))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        var saved = mapper.readTree(mvc.perform(post("/api/strategies").contentType(MediaType.APPLICATION_JSON).content(strategy))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = saved.path("id").asLong();
        var run = mapper.readTree(mvc.perform(post("/api/strategies/" + id + "/backtests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"symbol\":\"005930\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-06-01\",\"executionMode\":\"NEXT_DAY_OPEN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("NO_DATA"))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/backtest/runs/" + run.path("id").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.strategy.strategy.originalPrompt")
                        .value("SMA 5일 20일 골든크로스 매수, 5% 손절"));
    }

    @Test void crossSiteRemoteAndUnmarkedRequestsCannotConsumeSubscription() throws Exception {
        for (var request : java.util.List.of(
                post("/api/strategy-assistant/interpret"),
                post("/api/strategy-assistant/interpret").header("X-Strategy-Local", "1").header("Origin", "https://evil.example"),
                post("/api/strategy-assistant/interpret").header("X-Strategy-Local", "1").with(r -> {r.setRemoteAddr("192.168.1.50"); return r;}),
                post("/api/strategy-assistant/interpret").header("X-Strategy-Local", "1").with(r -> {r.setServerName("evil.example"); return r;}))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"입력\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ONLY"));
        }
        verifyNoInteractions(codex);
    }

    @Test void providerFailureIsSanitizedAndDoesNotCreateStrategies() throws Exception {
        when(codex.interpret(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT"));
        mvc.perform(post("/api/strategy-assistant/interpret").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"입력\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("CODEX_TIMEOUT"));
        mvc.perform(get("/api/strategies")).andExpect(jsonPath("$.length()").value(0));
    }
}
