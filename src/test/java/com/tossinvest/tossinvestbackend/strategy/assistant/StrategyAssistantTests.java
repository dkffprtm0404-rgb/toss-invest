package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrategyAssistantTests {
    final CodexClient codex = mock(CodexClient.class);
    final StrategyAssistantService service = new StrategyAssistantService(codex,
            new StrategyJson(new ObjectMapper()), new StrategyValidator());
    static final String OUTPUT = """
            {"strategy":{"schemaVersion":1,"name":"골든크로스","originalPrompt":null,
             "entry":{"operator":null,"conditions":[{"type":"MA_CROSS","averageType":"SMA",
              "shortPeriod":5,"longPeriod":20,"direction":"UP"}]},"exit":null,
             "risk":{"stopLoss":{"rate":-0.05}}},"questions":[],"unsupported":[]}
            """;

    @Test void validatesGeneratedDefinitionAndPreservesUserInputInsteadOfModelPrompt() {
        when(codex.interpret(anyString())).thenReturn(OUTPUT);
        var result = service.interpret(new StrategyAssistantService.Request("원문 그대로 ", "SMA 5일/20일"));
        assertThat(result.ready()).isTrue();
        assertThat(result.strategy().originalPrompt()).isEqualTo("원문 그대로 \n\n[보완 입력]\nSMA 5일/20일");
        assertThat(result.strategy().risk().stopLoss().rate()).isEqualByComparingTo("-0.05");
    }

    @Test void missingPeriodsStayMissingAndCannotBeMarkedReady() {
        when(codex.interpret(anyString())).thenReturn(OUTPUT.replace("\"shortPeriod\":5", "\"shortPeriod\":null"));
        var result = service.interpret(new StrategyAssistantService.Request("골든크로스 매수", null));
        assertThat(result.ready()).isFalse();
        assertThat(result.issues()).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0].shortPeriod");
    }

    @Test void unsupportedConditionsBlockEvenWhenPartialStrategyIsValid() {
        when(codex.interpret(anyString())).thenReturn(OUTPUT.replace("\"unsupported\":[]", "\"unsupported\":[\"MACD는 지원하지 않습니다\"]"));
        assertThat(service.interpret(new StrategyAssistantService.Request("MACD와 골든크로스", null)).ready()).isFalse();
    }

    @Test void rejectsMalformedModelOutputIncludingCoercionsAndUnknownFields() {
        for (String bad : List.of("not json", OUTPUT.replace("\"shortPeriod\":5", "\"shortPeriod\":\"5\""),
                OUTPUT.replace("\"shortPeriod\":5", "\"shortPeriod\":5.5"),
                OUTPUT.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"surpriseFilter\":true"),
                OUTPUT.replace("\"questions\":[]", "\"questions\":null"))) {
            when(codex.interpret(anyString())).thenReturn(bad);
            assertThatThrownBy(() -> service.interpret(new StrategyAssistantService.Request("입력", null)))
                    .isInstanceOf(AssistantException.class).hasMessageContaining("응답");
        }
    }

    @Test void emptyAndOversizedInputsAreRejectedBeforeModelUse() {
        for (String bad : List.of(" ", "a".repeat(6001))) {
            assertThatThrownBy(() -> service.interpret(new StrategyAssistantService.Request(bad, null)))
                    .isInstanceOf(StrategyValidationException.class);
        }
        verifyNoInteractions(codex);
    }
}
