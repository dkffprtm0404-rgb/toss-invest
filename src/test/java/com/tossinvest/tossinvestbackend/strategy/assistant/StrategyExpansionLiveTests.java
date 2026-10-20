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
    @Test void clarifiedTrendTemplateProducesAllSixAndConditions() throws Exception {
        var mapper = new ObjectMapper();
        var client = new CodexClient(new CodexProcessFactory(System.getenv().getOrDefault("STRATEGY_CODEX_EXECUTABLE", "codex")),
                mapper, "gpt-5.6-terra", 120000);
        var service = new StrategyAssistantService(client, new StrategyJson(mapper), new StrategyValidator());
        var result = service.interpret(new StrategyAssistantService.Request("""
                현재가 > SMA50&#x20;
                1. &#x20;SMA50 > SMA150&#x20;
                2. &#x20;SMA150 > SMA200&#x20;
                3. &#x20;SMA200 상승 중&#x20;
                4. &#x20;현재가가 52주 최고가의 25% 이내&#x20;
                5. &#x20;현재가가 52주 최저가 대비 30% 이상
                """, "모두 충족하면 진입하는 AND 조건. SMA200 상승 기간은 달력상 1달: 현재 SMA200이 한 달 전보다 높다. 휴장일은 그 이전 최근 거래일. 일봉 종가 기준, 52주 고가·저가는 당일 제외. 별도 청산과 위험관리 조건 없음."));
        Files.writeString(Path.of("build/strategy-trend-live.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertThat(result.ready()).as("questions=%s, unsupported=%s, issues=%s", result.questions(), result.unsupported(), result.issues()).isTrue();
        assertThat(result.strategy().entry().operator()).isEqualTo(StrategyDefinition.Operator.AND);
        assertThat(result.strategy().entry().conditions()).hasSize(6);
        assertThat(result.strategy().entry().conditions()).contains(
                new StrategyDefinition.PriceMovingAverage(StrategyDefinition.AverageType.SMA, 50, StrategyDefinition.Comparison.GT),
                new StrategyDefinition.MovingAverageCompare(StrategyDefinition.AverageType.SMA, 50, 150, StrategyDefinition.Comparison.GT),
                new StrategyDefinition.MovingAverageCompare(StrategyDefinition.AverageType.SMA, 150, 200, StrategyDefinition.Comparison.GT),
                new StrategyDefinition.MovingAverageTrend(StrategyDefinition.AverageType.SMA, 200, 1, StrategyDefinition.Direction.UP));
        assertThat(result.strategy().entry().conditions()).anyMatch(c -> c instanceof StrategyDefinition.RangeRatio r
                && r.period() == 52 && r.periodUnit() == StrategyDefinition.PeriodUnit.CALENDAR_WEEKS && r.priceField() == StrategyDefinition.PriceField.HIGH
                && r.multiplier().compareTo(new java.math.BigDecimal("0.75")) == 0 && r.comparison() == StrategyDefinition.Comparison.GTE);
        assertThat(result.strategy().entry().conditions()).anyMatch(c -> c instanceof StrategyDefinition.RangeRatio r
                && r.period() == 52 && r.periodUnit() == StrategyDefinition.PeriodUnit.CALENDAR_WEEKS && r.priceField() == StrategyDefinition.PriceField.LOW
                && r.multiplier().compareTo(new java.math.BigDecimal("1.30")) == 0 && r.comparison() == StrategyDefinition.Comparison.GTE);
        var batchService = new StrategyBatchService(client, new StrategyJson(mapper), service);
        String explicitPrompt;
        try (var input = getClass().getResourceAsStream("/demo/strategy-trend-template.json")) {
            explicitPrompt = mapper.readTree(input).path("originalPrompt").asText();
        }
        var batch = batchService.interpret(new StrategyBatchService.Request(explicitPrompt));
        Files.writeString(Path.of("build/strategy-trend-batch-live.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        assertThat(batch.items()).hasSize(1);
        var draft = batch.items().get(0).draft();
        assertThat(draft.ready()).as("batch questions=%s, unsupported=%s, issues=%s", draft.questions(), draft.unsupported(), draft.issues()).isTrue();
        assertThat(draft.strategy().entry().operator()).isEqualTo(StrategyDefinition.Operator.AND);
        assertThat(draft.strategy().entry().conditions()).usingRecursiveComparison().ignoringCollectionOrder()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class).isEqualTo(result.strategy().entry().conditions());
        var missing = service.interpret(new StrategyAssistantService.Request("일봉 종가 기준. 현재가>SMA50 AND SMA50>SMA150 AND SMA150>SMA200 AND SMA200 상승 중. 별도 청산과 위험관리 없음.", null));
        Files.writeString(Path.of("build/strategy-trend-missing-live.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(missing));
        assertThat(missing.ready()).isFalse();
        assertThat(missing.unsupported()).isEmpty();
        assertThat(missing.questions()).isNotEmpty();
        assertThat(missing.strategy().entry().conditions()).hasSize(4).anyMatch(c -> c instanceof StrategyDefinition.MovingAverageTrend ma && ma.lookbackMonths() == null);
    }
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
