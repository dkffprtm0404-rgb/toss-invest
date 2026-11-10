package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
class MarketBriefingServiceTests {
    @Autowired MarketBriefingRepository repository;
    final MarketBriefingSources sources=mock(MarketBriefingSources.class);
    final MarketTradingCalendar calendar=mock(MarketTradingCalendar.class);
    final CodexClient codex=mock(CodexClient.class);
    final StrategyJson json=new StrategyJson(new ObjectMapper().findAndRegisterModules());
    final Clock clock=Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    MarketBriefingService service;
    final LocalDate date=LocalDate.of(2026,11,5);
    final MarketBriefing.Source quote=new MarketBriefing.Source("KOSPI","QUOTE","코스피","코스피 종가 100, 전일 대비 -1%",
            "https://finance.yahoo.com/quote/%5EKS11/",Instant.parse("2026-11-04T06:30:00Z"),new BigDecimal("100"),new BigDecimal("-0.01"),"pt");
    final MarketBriefing.Source news=new MarketBriefing.Source("news-1","NEWS","연합뉴스","반도체 수출 확대",
            "https://www.yna.co.kr/view/test",Instant.parse("2026-11-04T23:00:00Z"),null,null,null);
    final String output="""
            {"items":[{"title":"국내장 점검","summary":"코스피 종가는 100이며 전일 대비 -1%입니다.","sourceIds":["KOSPI"]},
            {"title":"주요 뉴스","summary":"반도체 수출 확대 소식을 확인할 필요가 있습니다.","sourceIds":["news-1"]}]}
            """;

    @BeforeEach void setup(){
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(call -> ((LocalDate)call.getArgument(0)).getDayOfWeek().getValue() <= 5);
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(quote,news),List.of()));
        when(codex.summarizeMarket(anyString())).thenReturn(output);
        when(codex.model()).thenReturn("test-model");
        service=new MarketBriefingService(repository,sources,calendar,codex,json,clock);
    }

    @Test void generatedBriefingHasSourcesAndIsStoredWithoutRegeneratingOnReadOrRetry(){
        var first=service.generateToday();
        assertThat(first.status()).isEqualTo("READY");
        assertThat(first.cutoffAt()).isEqualTo(Instant.parse("2026-11-04T23:30:00Z"));
        assertThat(first.items()).hasSize(2);
        assertThat(first.sources()).containsExactly(quote,news);
        assertThat(service.get(date)).isEqualTo(first);
        assertThat(service.latest()).isEqualTo(first);
        assertThat(service.generateToday()).isEqualTo(first);
        verify(codex,times(1)).summarizeMarket(anyString());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void staleDomesticQuotesArePartialWithoutRelabelingTheirObservationDate(){
        var oldQuote=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(oldQuote,news),List.of()));
        var result=service.generateToday();
        assertThat(result.date()).isEqualTo(date);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.sources().get(0).observedAt()).isEqualTo(Instant.parse("2026-11-03T06:30:00Z"));
        assertThat(result.warnings()).anySatisfy(warning->assertThat(warning).contains("코스피","2026-11-03","2026-11-04"));
        assertThat(service.latest()).isEqualTo(result);
    }

    @Test void oldSavedReadySnapshotAlsoShowsMissingLatestSessionWithoutRegenerating(){
        var oldQuote=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant(),clock.instant(),"test-model",List.of(),List.of(oldQuote),List.of(),null);
        repository.saveAndFlush(new MarketBriefingEntity(date,json.write(snapshot)));
        var displayed=service.generateToday();
        assertThat(displayed.date()).isEqualTo(date);
        assertThat(displayed.status()).isEqualTo("PARTIAL");
        assertThat(displayed.warnings()).anySatisfy(warning->assertThat(warning).contains("2026-11-03","2026-11-04"));
        verifyNoInteractions(sources,codex);
        assertThat(json.stored(repository.findById(date).orElseThrow().getSnapshotJson(),MarketBriefing.class).status()).isEqualTo("READY");
    }

    @Test void previousTradingSessionSkipsWeekendsAndExchangeHolidays(){
        when(calendar.isTradingDay(LocalDate.of(2026,11,4))).thenReturn(false);
        var oldQuote=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(oldQuote,news),List.of()));
        assertThat(service.generateToday().status()).isEqualTo("READY");
        var mondayClock=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),MarketBriefingService.KST);
        var friday=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-06T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(mondayClock.instant(),List.of(friday,news),List.of()));
        var monday=new MarketBriefingService(repository,sources,calendar,codex,json,mondayClock);
        assertThat(monday.generateToday().status()).isEqualTo("READY");
    }
    @Test void unknownCitationOrInventedNumberCannotBePublished(){
        when(codex.summarizeMarket(anyString())).thenReturn(output.replace("\"KOSPI\"","\"absent\""));
        assertThat(service.generateToday().status()).isEqualTo("FAILED");
        when(codex.summarizeMarket(anyString())).thenReturn(output.replace("종가는 100","종가는 999"));
        assertThat(service.generateToday().status()).isEqualTo("FAILED");
        assertThat(service.get(date).items()).isEmpty();
    }

    @Test void englishMonthNamesCanBeTranslatedWithoutPermittingInventedMonths(){
        var article=new MarketBriefing.Source("news-1","NEWS","CNBC","October inflation report is due Tuesday",
                news.url(),news.observedAt(),null,null,null);
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(article),List.of()));
        String translated="{\"items\":[{\"title\":\"미국 물가\",\"summary\":\"10월 물가 보고서가 화요일 발표될 예정입니다.\",\"sourceIds\":[\"news-1\"]}]}";
        when(codex.summarizeMarket(anyString())).thenReturn(translated.replace("10월","11월"));
        assertThat(service.generateToday().status()).isEqualTo("FAILED");
        when(codex.summarizeMarket(anyString())).thenReturn(translated);
        assertThat(service.generateToday().status()).isEqualTo("READY");
    }

    @Test void providerFailureCanBeRetriedWithoutLosingAvailableEvidence(){
        when(codex.summarizeMarket(anyString())).thenThrow(new AssistantException("CODEX_BUSY")).thenReturn(output);
        var failed=service.generateToday();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.sources()).hasSize(2);
        assertThat(failed.error()).doesNotContain("Exception","token","jdbc");
        assertThat(service.generateToday().status()).isEqualTo("READY");
    }

    @Test void noSourcesDoesNotAskTheModelToInventAMarket(){
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(),List.of("시세·뉴스 수집 실패")));
        var result=service.generateToday();
        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.items()).isEmpty();
        verify(codex,never()).summarizeMarket(anyString());
    }

    @Test void partialCollectionIsVisibleAndAnOldDateIsNotRelabeledToday(){
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(quote,news),List.of("미국 지수 수집 실패")));
        var first=service.generateToday();
        assertThat(first.status()).isEqualTo("PARTIAL");
        assertThat(first.warnings()).contains("미국 지수 수집 실패");
        var tomorrow=new MarketBriefingService(repository,sources,calendar,codex,json,Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        assertThat(tomorrow.latest().date()).isEqualTo(date);
    }

    @Test void closedDayDoesNotCollectDataOrGenerateAnAiCall(){
        when(calendar.isTradingDay(date)).thenReturn(false);
        assertThat(service.generateToday().status()).isEqualTo("CLOSED");
        verifyNoInteractions(sources,codex);
        assertThat(repository.count()).isZero();
    }

    @Test void scheduledGenerationWaitsUntil0830AndRetriesOnlyThreeTimes(){
        var morning=new MarketBriefingService(repository,sources,calendar,codex,json,Clock.fixed(Instant.parse("2026-11-04T23:29:00Z"),ZoneId.of("Asia/Seoul")));
        morning.generateIfDue();
        verifyNoInteractions(sources,codex);
        when(codex.summarizeMarket(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT"));
        for(int i=0;i<5;i++)service.generateIfDue();
        verify(codex,times(3)).summarizeMarket(anyString());
        doReturn(output).when(codex).summarizeMarket(anyString());
        assertThat(service.generateToday().status()).isEqualTo("READY"); // explicit retry remains available.
    }
}
