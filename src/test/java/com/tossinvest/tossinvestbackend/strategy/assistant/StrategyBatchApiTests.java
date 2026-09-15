package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:batch;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@MockitoBean(types = {PaperTradingScheduler.class, ScalpingScheduler.class})
@Transactional
class StrategyBatchApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CodexClient codex;

    @Test void batchEndpointRejectsMissingPromptBeforeCallingCodex() throws Exception {
        mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(codex);
    }

    @Test void batchEndpointRequiresLocalMarkedRequest() throws Exception {
        mvc.perform(post("/api/strategy-assistant/interpret-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"두 전략\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ONLY"));
        verifyNoInteractions(codex);
    }

    @Test void supportedDraftIsIndependentFromAnotherDraftsQuestionsAndUnsupportedRules() throws Exception {
        var output = output("국내주식\n", "A 원문", "B 원문");
        ((ObjectNode) output.at("/items/1")).set("strategy", mapper.nullNode());
        ((ObjectNode) output.at("/items/1")).putArray("questions").add("정확한 손절률은?");
        ((ObjectNode) output.at("/items/1")).putArray("unsupported").add("상대강도 리밸런싱은 다음 단계입니다.");
        when(codex.interpretBatch(anyString())).thenReturn(output.toString());
        mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("prompt", "국내주식\nA 원문\n\nB 원문"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].draft.ready").value(true))
                .andExpect(jsonPath("$.items[0].draft.questions.length()").value(0))
                .andExpect(jsonPath("$.items[0].draft.strategy.originalPrompt").value("국내주식\nA 원문"))
                .andExpect(jsonPath("$.items[1].draft.ready").value(false))
                .andExpect(jsonPath("$.items[1].draft.questions[0]").value("정확한 손절률은?"))
                .andExpect(jsonPath("$.items[1].prompt").value("국내주식\n\n\nB 원문"));
        verify(codex, times(1)).interpretBatch(anyString());
        verify(codex, never()).interpret(anyString());
    }

    @Test void alteredOmittedOverlappingOrReorderedSourceCannotBecomeSavedStrategyText() throws Exception {
        for (var texts : java.util.List.of(new String[]{"A 수정", "B 원문"}, new String[]{"A", "B 원문"},
                new String[]{"B 원문", "A 원문"}, new String[]{"A 원문", "A 원문"})) {
            when(codex.interpretBatch(anyString())).thenReturn(output("", texts).toString());
            mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"A 원문 B 원문\"}"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CODEX_INVALID_RESPONSE"));
        }
    }

    @Test void noStrategyReturnsAGlobalQuestionAndCannotPretendSuccessWithAnEmptyList() throws Exception {
        var output = output(""); output.withArray("questions").add("전략을 입력해 주세요.");
        when(codex.interpretBatch(anyString())).thenReturn(output.toString());
        mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"안녕하세요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.questions[0]").value("전략을 입력해 주세요."));
        output.putArray("questions"); when(codex.interpretBatch(anyString())).thenReturn(output.toString());
        mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"안녕하세요\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test void fullLengthPromptWithSharedContextDoesNotGrowPastTheInputLimit() throws Exception {
        String context = "국내주식\n";
        String text = "가".repeat(6000 - context.length());
        var output = output(context, text); ((ObjectNode) output.at("/items/0")).put("title", "전략");
        when(codex.interpretBatch(anyString())).thenReturn(output.toString());
        mvc.perform(post("/api/strategy-assistant/interpret-batch").header("X-Strategy-Local", "1")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("prompt", context + text))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].prompt").value(context + text));
    }

    private ObjectNode output(String context, String... texts) throws Exception {
        var result = mapper.createObjectNode(); result.put("sharedContext", context); result.putArray("questions");
        var items = result.putArray("items");
        for (String text : texts) {
            ObjectNode item = (ObjectNode) mapper.readTree(StrategyAssistantTests.OUTPUT);
            item.put("title", text); item.put("sourceText", text); items.add(item);
        }
        return result;
    }
}
