package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexProcessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real model check with public captured evidence and an isolated repository double. */
@EnabledIfEnvironmentVariable(named="RUN_MARKET_BRIEFING_LIVE",matches="true")
class MarketBriefingLiveTests {
    @Test void subscriptionSummaryPassesTheSameEvidenceValidationUsedByTheApplication()throws Exception{
        var mapper=new ObjectMapper().findAndRegisterModules();var json=new StrategyJson(mapper);
        var captured=json.read(Files.readString(Path.of("build/market-briefing-live-input.json")),MarketBriefing.class);
        var inputs=mock(MarketBriefingSources.class);var calendar=mock(MarketTradingCalendar.class);
        var repository=mock(MarketBriefingRepository.class);
        when(inputs.collect(any())).thenReturn(new MarketBriefing.Input(captured.collectedAt(),captured.sources(),captured.warnings()));
        when(calendar.isTradingDay(any())).thenReturn(true);when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        var client=spy(new CodexClient(new CodexProcessFactory("codex"),mapper,"gpt-5.6-terra",120000));
        doAnswer(call->{String output=(String)call.callRealMethod();Files.writeString(Path.of("build/market-briefing-live-output.json"),output);return output;})
                .when(client).summarizeMarket(anyString());
        var clock=Clock.fixed(captured.date().atTime(9,0).atZone(MarketBriefingService.KST).toInstant(),MarketBriefingService.KST);
        var result=new MarketBriefingService(repository,inputs,calendar,client,json,clock).generateToday();
        Files.writeString(Path.of("build/market-briefing-live-result.json"),json.write(result));
        assertThat(result.status()).withFailMessage("Live briefing rejected: %s; inspect build/market-briefing-live-output.json",result.error())
                .isIn("READY","PARTIAL");
        assertThat(result.items()).isNotEmpty();
    }
}
