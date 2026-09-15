package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: one batch interpretation using existing ChatGPT-login Codex. */
@EnabledIfEnvironmentVariable(named = "RUN_STRATEGY_BATCH_LIVE", matches = "true")
class StrategyBatchLiveTests {
    @Test void originalSixStrategiesSplitWithoutLosingTextOrSharingUnsupportedConditions() throws Exception {
        var mapper = new ObjectMapper(); var json = new StrategyJson(mapper);
        var client = new CodexClient(new CodexProcessFactory(System.getenv().getOrDefault("STRATEGY_CODEX_EXECUTABLE", "codex")), mapper, "gpt-5.6-terra", 120000);
        var service = new StrategyBatchService(client, json, new StrategyAssistantService(client, json, new StrategyValidator()));
        String prompt = """
                국내주식 기준이다.

                0 일 고가 돌파 (KOSPI/KOSDAQ): 종가가 최근 20 일 고가를 상향 돌파하면 매수, 진입가 대비 -5% 손절, 고점 대비 -20% 추적손절로 추세 이탈 시 매도.

                52 주 신고가 모멘텀: 종가가 52 주 신고가를 경신하면 매수, -5% 고정 손절, 10 일 저가 이탈 시 매도로 모멘텀 유지.

                SMA 골든크로스 + 장기 필터: 5 일 SMA 가 20 일 SMA 를 상향 돌파하되 20 일 SMA 가 200 일 SMA 위에 있을 때만 매수, 5 일 SMA 가 20 일 SMA 를 하향 돌파하면 매도, 진입가 대비 -5% 손절.

                상대강도 상위주 리밸런싱: 매주 6 개월 수익률 상위 10 개 종목을 200 일 SMA 위인 종목만 골라 매수, -8% 손절, 순위에서 밀리면 매도.

                변동성 돌파 (ATR): 전일 종가 + 0.5×ATR(14) 를 돌파하면 매수, 진입가 대비 -5% 또는 진입가 - 1.5×ATR 손절, 10 일 저가 이탈 시 매도.

                50/200 일 골든크로스 (중장기): 50 일 SMA 가 200 일 SMA 를 상향 돌파하면 매수, -8~10% 손절, 50 일 SMA 가 200 일 SMA 를 하향 돌파하면 매도.
                """;
        var result = service.interpret(new StrategyBatchService.Request(prompt));
        Files.writeString(Path.of("build/strategy-batch-live.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertThat(result.items()).hasSize(6);
        assertThat(result.questions()).isEmpty();
        assertThat(result.items().get(3).draft().unsupported()).isEmpty();
        assertThat(result.items().get(3).draft().strategy().portfolio()).isNotNull();
        assertThat(result.items().get(3).draft().questions()).isNotEmpty();
        assertThat(result.items().get(5).draft().questions()).isNotEmpty();
        for (int i : new int[]{0,1,2,4,5}) assertThat(result.items().get(i).draft().unsupported()).as("item %s",i).isEmpty();
        assertThat(result.items().get(0).prompt()).contains("0 일 고가 돌파", "최근 20 일 고가");
        assertThat(result.items().get(2).draft().ready()).isTrue();
    }
}
