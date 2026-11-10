package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MacdBollingerAssistantTests {
    final CodexClient codex=mock(CodexClient.class);
    final StrategyAssistantService service=new StrategyAssistantService(codex,new StrategyJson(new ObjectMapper()),new StrategyValidator());
    static final String OUTPUT="""
            {"strategy":{"schemaVersion":2,"name":"MACD와 볼린저밴드","entry":{"operator":"AND","conditions":[
              {"type":"MACD","fastPeriod":12,"slowPeriod":26,"signalPeriod":9,"value":"LINE","reference":"SIGNAL","threshold":null,"comparison":"CROSS_ABOVE"},
              {"type":"BOLLINGER","period":20,"multiplier":2,"band":"LOWER","comparison":"LTE"}]}},"questions":[],"unsupported":[]}
            """;
    @Test void supportedModelOutputBecomesReadyWithoutLosingExplicitIndicatorParameters() {
        when(codex.interpret(anyString())).thenReturn(OUTPUT);
        var result=assertDoesNotThrow(()->service.interpret(new StrategyAssistantService.Request("일봉 MACD와 볼린저밴드 조건",null)));
        assertThat(result.ready()).isTrue();
        assertThat(result.strategy().entry().conditions()).hasSize(2);
        assertThat(result.strategy().originalPrompt()).isEqualTo("일봉 MACD와 볼린저밴드 조건");
        assertThat(result.questions()).isEmpty();
        assertThat(result.unsupported()).isEmpty();
    }
    @Test void missingIndicatorPeriodsStayMissingUntilClarified() {
        when(codex.interpret(anyString())).thenReturn(OUTPUT.replace("\"fastPeriod\":12","\"fastPeriod\":null").replace("\"multiplier\":2","\"multiplier\":null").replace("\"questions\":[]","\"questions\":[\"MACD 단기 기간과 볼린저 표준편차 배수를 입력해 주세요.\"]"));
        var result=assertDoesNotThrow(()->service.interpret(new StrategyAssistantService.Request("MACD와 볼린저밴드",null)));
        assertThat(result.ready()).isFalse();
        assertThat(result.issues()).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0].fastPeriod","entry.conditions[1].multiplier");
        assertThat(result.questions()).isNotEmpty();
        assertThat(result.unsupported()).isEmpty();
    }
}
