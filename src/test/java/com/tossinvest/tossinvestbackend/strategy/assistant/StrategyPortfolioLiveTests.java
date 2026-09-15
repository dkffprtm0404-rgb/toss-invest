package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_STRATEGY_PORTFOLIO_LIVE",matches="true")
class StrategyPortfolioLiveTests {
    @Test void explicitKoreanPortfolioIsReadyWithVersionThree() throws Exception {
        var mapper=new ObjectMapper();var json=new StrategyJson(mapper);
        var client=new CodexClient(new CodexProcessFactory(System.getenv().getOrDefault("STRATEGY_CODEX_EXECUTABLE","codex")),mapper,"gpt-5.6-terra",120000);
        var service=new StrategyAssistantService(client,json,new StrategyValidator());
        String prompt="국내 KOSPI와 KOSDAQ 통합 상대강도 전략. 종가가 200거래일 SMA 위인 종목을 먼저 걸러서 달력 6개월 종가 수익률 상위 10종목을 선정한다. 매주 마지막 거래일 종가로 순위를 계산하고 주간 순위에서 밀리면 매도한다. 각 종목은 계좌의 1/10 동일 비중으로 조정하며 10개보다 적으면 남은 몫은 현금이다. 평균 매입가격 대비 -8% 손절은 매일 종가로 판단한다. 다른 진입·청산 조건은 없다. 체결 방식, 초기자금, 비용, 시장 구성 자료는 실행 화면에서 지정한다.";
        var result=service.interpret(new StrategyAssistantService.Request(prompt,null));
        Files.writeString(Path.of("build/strategy-portfolio-live.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertThat(result.ready()).as(result.toString()).isTrue();assertThat(result.strategy().schemaVersion()).isEqualTo(3);
        assertThat(result.strategy().portfolio().topN()).isEqualTo(10);
        assertThat(result.strategy().portfolio().selectionOrder()).isEqualTo(StrategyDefinition.SelectionOrder.FILTER_THEN_RANK);
        assertThat(result.strategy().portfolio().rebalanceTiming()).isEqualTo(StrategyDefinition.RebalanceTiming.WEEK_END);
    }
}
