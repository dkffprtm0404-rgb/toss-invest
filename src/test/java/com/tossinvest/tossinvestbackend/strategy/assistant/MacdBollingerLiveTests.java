package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: exercises the real natural-language client and its structured-output schema. */
@EnabledIfEnvironmentVariable(named="RUN_STRATEGY_INDICATORS_LIVE",matches="true")
class MacdBollingerLiveTests {
    @Test void explicitIndicatorsInterpretAndMissingPeriodsReturnClarificationInsteadOfDefaults() throws Exception {
        var mapper=new ObjectMapper();
        var client=new CodexClient(new CodexProcessFactory(System.getenv().getOrDefault("STRATEGY_CODEX_EXECUTABLE","codex")),mapper,"gpt-5.6-terra",120000);
        var service=new StrategyAssistantService(client,new StrategyJson(mapper),new StrategyValidator());
        var result=service.interpret(new StrategyAssistantService.Request("""
                국내 개별 종목 완성 일봉 종가 기준. 다음 두 조건을 모두 충족하면 매수(AND):
                MACD의 단기 EMA 12봉, 장기 EMA 26봉, 시그널 EMA 9봉으로 계산한 MACD선이 시그널선을 상향 교차.
                동시에 기간 20봉, 모집단 표준편차 배수 2인 볼린저밴드의 하단 이하 종가.
                별도 청산 및 위험관리 조건은 없음.
                """,null));
        Files.writeString(Path.of("build/strategy-indicators-live.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertThat(result.ready()).as("questions=%s unsupported=%s issues=%s",result.questions(),result.unsupported(),result.issues()).isTrue();
        var conditions=mapper.valueToTree(result.strategy()).path("entry").path("conditions");
        assertThat(conditions.size()).isEqualTo(2);
        var macd=java.util.stream.StreamSupport.stream(conditions.spliterator(),false).filter(n->n.path("type").asText().equals("MACD")).findFirst().orElseThrow();
        assertThat(macd.path("fastPeriod").asInt()).isEqualTo(12);
        assertThat(macd.path("slowPeriod").asInt()).isEqualTo(26);
        assertThat(macd.path("signalPeriod").asInt()).isEqualTo(9);
        assertThat(macd.path("value").asText()).isEqualTo("LINE");
        assertThat(macd.path("reference").asText()).isEqualTo("SIGNAL");
        assertThat(macd.path("comparison").asText()).isEqualTo("CROSS_ABOVE");
        var bollinger=java.util.stream.StreamSupport.stream(conditions.spliterator(),false).filter(n->n.path("type").asText().equals("BOLLINGER")).findFirst().orElseThrow();
        assertThat(bollinger.path("period").asInt()).isEqualTo(20);
        assertThat(bollinger.path("multiplier").decimalValue()).isEqualByComparingTo("2");
        assertThat(bollinger.path("band").asText()).isEqualTo("LOWER");
        assertThat(bollinger.path("comparison").asText()).isEqualTo("LTE");
        var missing=service.interpret(new StrategyAssistantService.Request("완성 일봉 종가 기준. MACD선이 시그널선을 상향 교차하면 매수. 기간은 아직 지정하지 않음. 별도 청산과 위험관리 없음.",null));
        Files.writeString(Path.of("build/strategy-indicators-missing-live.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(missing));
        assertThat(missing.ready()).isFalse();
        assertThat(missing.unsupported()).isEmpty();
        assertThat(missing.questions()).isNotEmpty();
        assertThat(missing.issues()).extracting(StrategyValidator.Issue::path).contains("entry.conditions[0].fastPeriod","entry.conditions[0].slowPeriod","entry.conditions[0].signalPeriod");
    }
}
