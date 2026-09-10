package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: consumes the existing ChatGPT Codex allowance, never an API key. */
@EnabledIfEnvironmentVariable(named = "RUN_CODEX_LIVE", matches = "true")
class CodexLiveTests {
    @Test void realSubscriptionAsksForMissingMovingAverageInputs() {
        var mapper = new ObjectMapper();
        var client = new CodexClient(new CodexProcessFactory("codex"), mapper, "gpt-5.6-terra", 120000);
        var service = new StrategyAssistantService(client, new StrategyJson(mapper), new StrategyValidator());
        var result = service.interpret(new StrategyAssistantService.Request("골든크로스에 매수하고 5% 손실이면 손절", null));
        assertThat(result.ready()).isFalse();
        assertThat(result.questions()).isNotEmpty();
        assertThat(result.issues()).isNotEmpty();
    }

    @Test void realSubscriptionInterpretsExplicitGoldenCrossWithoutImplicitRules() throws Exception {
        var mapper = new ObjectMapper();
        var client = new CodexClient(new CodexProcessFactory("codex"), mapper, "gpt-5.6-terra", 120000);
        assertThat(client.status().ready()).isTrue();
        var service = new StrategyAssistantService(client, new StrategyJson(mapper), new StrategyValidator());
        var result = service.interpret(new StrategyAssistantService.Request(
                "종가 기준 SMA 5일선이 SMA 20일선을 상향 돌파하면 매수하고, 진입가 대비 5% 손실이면 손절한다. 다른 진입 필터나 청산 조건은 없다.", null));
        assertThat(result.ready()).isTrue();
        assertThat(result.strategy().entry().conditions()).hasSize(1);
        assertThat(result.strategy().exit()).isNull();
        assertThat(result.strategy().risk().stopLoss().rate()).isEqualByComparingTo("-0.05");
        Files.writeString(Path.of("build/codex-live-draft.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
