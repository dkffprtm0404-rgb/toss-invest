package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Opt-in verification using the existing ChatGPT-login client; no API key fallback. */
@EnabledIfEnvironmentVariable(named = "RUN_STRATEGY_EXPANSION_LIVE", matches = "true")
class StrategyExpansionLiveTests {
    @Test void explicitDomesticStrategiesProduceExecutableExpandedRules() throws Exception {
        var mapper = new ObjectMapper();
        var client = new CodexClient(new CodexProcessFactory(System.getenv().getOrDefault("STRATEGY_CODEX_EXECUTABLE", "codex")),
                mapper, "gpt-5.6-terra", 120000);
        var service = new StrategyAssistantService(client, new StrategyJson(mapper), new StrategyValidator());
        List<String> prompts = List.of(
                "KOSPI/KOSDAQ 개별 종목 일봉 전략. 당일 제외 직전 20거래일 최고 고가를 종가가 초과하면 매수한다. 진입가 대비 -5% 종가 손절, 진입 후 일중 최고가 대비 -20% 종가 추적손절. 그 외 조건 없음.",
                "국내 개별 종목 일봉 전략. 종가가 당일 제외 과거 달력상 52주 최고 고가를 초과하면 매수, 진입가 대비 -5% 종가 손절, 종가가 당일 제외 직전 10거래일 최저 저가 미만이면 매도. 그 외 조건 없음.",
                "국내 개별 종목 일봉. 종가 기준 SMA 5일선이 SMA 20일선을 상향 교차하고 동시에 SMA 20일선이 SMA 200일선보다 높을 때 매수. SMA 5일선이 SMA 20일선을 하향 교차하면 매도. 진입가 대비 -5% 종가 손절. 그 외 조건 없음.",
                "국내 개별 종목 일봉. 종가가 전일 종가 + 0.5 × 전일 WILDER ATR(14)를 초과하면 매수. 진입 직전 완성 봉의 WILDER ATR(14)을 진입 때 고정해 진입가 - 1.5 × ATR 이하 종가에서 손절. -5% 고정 손절도 함께 사용해 둘 중 하나만 충족해도 종가 손절. 종가가 당일 제외 직전 10거래일 최저 저가 미만이면 매도. 다른 조건 없음.");
        for (int i = 0; i < prompts.size(); i++) {
            var result = service.interpret(new StrategyAssistantService.Request(prompts.get(i), null));
            Files.writeString(Path.of("build/strategy-expansion-live-" + i + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            assertThat(result.ready()).as("prompt %s, questions=%s, unsupported=%s, issues=%s", i, result.questions(), result.unsupported(), result.issues()).isTrue();
            assertThat(result.strategy().schemaVersion()).isEqualTo(2);
            if (i == 0) assertThat(result.strategy().risk().trailingStop().peakBasis()).isEqualTo(StrategyDefinition.PeakBasis.HIGH);
            if (i == 1) assertThat(result.strategy().entry().conditions()).anyMatch(c -> c instanceof StrategyDefinition.RangeBreakout r
                    && r.period() == 52 && r.periodUnit() == StrategyDefinition.PeriodUnit.CALENDAR_WEEKS);
            if (i == 2) assertThat(result.strategy().entry().conditions()).anyMatch(c -> c instanceof StrategyDefinition.MovingAverageCompare);
            if (i == 3) assertThat(result.strategy().risk().atrStop().multiplier()).isEqualByComparingTo("1.5");
        }
    }
}
