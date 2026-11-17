package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import com.tossinvest.tossinvestbackend.strategy.assistant.CodexClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
class MarketBriefingServiceTests {
    @Autowired MarketBriefingRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
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
        service=new MarketBriefingService(repository,sources,calendar,codex,json,clock,transactionManager);
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

    @Test void readingAnOldSavedReadySnapshotWarnsWithoutRegenerating(){
        var oldQuote=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant(),clock.instant(),"test-model",List.of(),List.of(oldQuote),List.of(),null);
        repository.saveAndFlush(new MarketBriefingEntity(date,json.write(snapshot)));
        var displayed=service.latest();
        assertThat(displayed.date()).isEqualTo(date);
        assertThat(displayed.status()).isEqualTo("PARTIAL");
        assertThat(displayed.warnings()).anySatisfy(warning->assertThat(warning).contains("2026-11-03","2026-11-04"));
        verifyNoInteractions(sources,codex);
        assertThat(json.stored(repository.findById(date).orElseThrow().getSnapshotJson(),MarketBriefing.class).status()).isEqualTo("READY");
    }

    @Test void retryUpgradesTodaysStaleQuoteAndArchivesTheExactPreviousSnapshot(){
        var old=storeStaleToday();
        var result=service.generateToday();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.sources()).containsExactly(quote,news);
        var entity=repository.findById(date).orElseThrow();
        assertThat(json.stored(entity.getSnapshotHistoryJson(),String[].class)).containsExactly(old);
        assertThat(service.latest()).isEqualTo(result);
        assertThat(service.generateToday()).isEqualTo(result);
        verify(codex,times(1)).summarizeMarket(anyString());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void scheduledRecoveryAlsoRechecksStalePublishedQuotes(){
        storeStaleToday();
        service.generateIfDue();
        assertThat(service.latest().sources()).containsExactly(quote,news);
        assertThat(service.latest().status()).isEqualTo("READY");
    }

    @Test void unsuccessfulRefreshNeverOverwritesThePublishedSnapshot(){
        var old=storeStaleToday();
        when(codex.summarizeMarket(anyString())).thenThrow(new AssistantException("CODEX_BUSY"));
        assertThat(service.generateToday().status()).isEqualTo("PARTIAL");
        var entity=repository.findById(date).orElseThrow();
        assertThat(entity.getSnapshotJson()).isEqualTo(old);
        assertThat(entity.getSnapshotHistoryJson()).isNull();
    }

    @Test void unchangedQuotesDoNotRegenerateTheAiOrReplaceTheSnapshot(){
        var old=storeStaleToday();
        var previous=json.stored(old,MarketBriefing.class);
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),previous.sources(),List.of()));
        for(int i=0;i<5;i++)service.generateIfDue();
        assertThat(repository.findById(date).orElseThrow().getSnapshotJson()).isEqualTo(old);
        verify(codex,never()).summarizeMarket(anyString());
        verify(sources,times(3)).collect(any());
    }

    @Test void refreshWithAMissingPreviouslyAvailableQuoteKeepsTheOldSnapshot(){
        var fx=new MarketBriefing.Source("USDKRW","QUOTE","원/달러","원/달러 일별 환율 1300",
                "https://m.stock.naver.com/marketindex/exchange/FX_USDKRW",Instant.parse("2026-11-04T14:59:59Z"),
                new BigDecimal("1300"),BigDecimal.ZERO,"KRW");
        var old=storeStaleToday();
        var previous=json.stored(old,MarketBriefing.class);
        var withFx=new MarketBriefing(date,previous.status(),previous.cutoffAt(),previous.generatedAt(),previous.collectedAt(),
                previous.model(),previous.items(),List.of(previous.sources().get(0),fx,news),previous.warnings(),previous.error());
        var entity=repository.findById(date).orElseThrow();
        String saved=json.write(withFx);entity.setSnapshotJson(saved);repository.saveAndFlush(entity);
        service.generateToday();
        assertThat(repository.findById(date).orElseThrow().getSnapshotJson()).isEqualTo(saved);
        verify(codex,never()).summarizeMarket(anyString());
    }

    @Test void staleExchangeRatesAlsoShowTheMissingPreviousTradingDate(){
        var fx=new MarketBriefing.Source("USDKRW","QUOTE","원/달러","원/달러 일별 환율 1300",
                "https://m.stock.naver.com/marketindex/exchange/FX_USDKRW",Instant.parse("2026-11-03T14:59:59Z"),
                new BigDecimal("1300"),BigDecimal.ZERO,"KRW");
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant(),clock.instant(),"test-model",List.of(),List.of(fx),List.of(),null);
        repository.saveAndFlush(new MarketBriefingEntity(date,json.write(snapshot)));
        var result=service.latest();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).anySatisfy(warning->assertThat(warning).contains("원/달러","2026-11-03","2026-11-04"));
    }

    @Test void legacyYahooFxCandleEndCannotMasqueradeAsTheFollowingDaysPrice(){
        var legacy=new MarketBriefing.Source("USDKRW","QUOTE","원/달러","원/달러 종가 1300",
                "https://finance.yahoo.com/quote/KRW%3DX/",Instant.parse("2026-11-04T00:00:00Z"),
                new BigDecimal("1300"),BigDecimal.ZERO,"KRW");
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant(),clock.instant(),"test-model",List.of(),List.of(legacy),List.of(),null);
        repository.saveAndFlush(new MarketBriefingEntity(date,json.write(snapshot)));
        assertThat(service.latest().warnings()).anySatisfy(warning->assertThat(warning).contains("2026-11-03","2026-11-04"));
        var fx=new MarketBriefing.Source("USDKRW","QUOTE","원/달러","원/달러 일별 환율 1344.5",
                "https://m.stock.naver.com/marketindex/exchange/FX_USDKRW",Instant.parse("2026-11-04T14:59:59Z"),
                new BigDecimal("1344.5"),BigDecimal.ZERO,"KRW");
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(fx),List.of()));
        when(codex.summarizeMarket(anyString())).thenReturn("""
                {"items":[{"title":"환율","summary":"원/달러 일별 환율은 1344.5입니다.","sourceIds":["USDKRW"]}]}
                """);
        assertThat(service.generateToday().sources()).containsExactly(fx);
        assertThat(repository.findById(date).orElseThrow().getSnapshotHistoryJson()).isNotNull();
    }

    @Test void recoveryPreservesSavedUsQuotesAndNewsInsteadOfReplacingTheirEvidence(){
        var old=storeStaleToday();
        var previous=json.stored(old,MarketBriefing.class);
        var us=new MarketBriefing.Source("SP500","QUOTE","S&P 500","S&P 500 종가 5000",
                "https://finance.yahoo.com/quote/%5EGSPC/",Instant.parse("2026-11-04T21:00:00Z"),
                new BigDecimal("5000"),BigDecimal.ZERO,"pt");
        old=storeTodaySources(List.of(previous.sources().get(0),us,news));
        var revisedUs=new MarketBriefing.Source(us.id(),us.kind(),us.label(),"S&P 500 종가 6000",us.url(),
                us.observedAt(),new BigDecimal("6000"),BigDecimal.ZERO,"pt");
        var revisedNews=new MarketBriefing.Source(news.id(),news.kind(),news.label(),"새로 수집한 다른 기사",
                news.url(),news.observedAt(),null,null,null);
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(quote,revisedUs,revisedNews),List.of()));

        var result=service.generateToday();

        assertThat(result.sources()).containsExactly(quote,us,news);
        assertThat(result.items()).hasSize(2);
        assertThat(json.stored(repository.findById(date).orElseThrow().getSnapshotHistoryJson(),String[].class)).containsExactly(old);
    }

    @Test void domesticRecoveryCanUseSavedNonTargetEvidenceWhenItsProviderIsUnavailable(){
        var previous=json.stored(storeStaleToday(),MarketBriefing.class);
        var us=new MarketBriefing.Source("SP500","QUOTE","S&P 500","S&P 500 종가 5000",
                "https://finance.yahoo.com/quote/%5EGSPC/",Instant.parse("2026-11-04T21:00:00Z"),
                new BigDecimal("5000"),BigDecimal.ZERO,"pt");
        storeTodaySources(List.of(previous.sources().get(0),us,news));
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(quote),List.of("미국 지수 수집 실패")));

        var result=service.generateToday();

        assertThat(result.sources()).containsExactly(quote,us,news);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).contains("미국 지수 수집 실패");
        verify(codex,times(1)).summarizeMarket(anyString());
    }

    @Test void consecutiveImprovementsKeepEveryExactSnapshotOnceAndReadsNeverAppendHistory(){
        var oldest=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-02T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        var original=" \n"+storeTodaySources(List.of(oldest,news))+"\n";
        var entity=repository.findById(date).orElseThrow();
        entity.setSnapshotJson(original);repository.saveAndFlush(entity);
        var intermediate=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(intermediate,news),List.of()));
        assertThat(service.generateToday().status()).isEqualTo("PARTIAL");
        var middle=repository.findById(date).orElseThrow().getSnapshotJson();
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(quote,news),List.of()));
        var latest=service.generateToday();
        assertThat(latest.status()).isEqualTo("READY");
        assertThat(service.get(date)).isEqualTo(latest);
        assertThat(service.latest()).isEqualTo(latest);
        assertThat(service.history()).containsExactly(latest);
        assertThat(service.generateToday()).isEqualTo(latest);
        assertThat(json.stored(repository.findById(date).orElseThrow().getSnapshotHistoryJson(),String[].class))
                .containsExactly(original,middle);
        verify(codex,times(2)).summarizeMarket(anyString());
    }

    @Test void collectionFailureOrARegressingQuoteLeavesTheOriginalBodyAndHistoryUntouched(){
        var original=storeStaleToday();
        when(sources.collect(any())).thenThrow(new IllegalStateException("synthetic provider failure"));
        service.generateToday();
        var older=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-02T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(older,news),List.of()));
        service.generateToday();
        var entity=repository.findById(date).orElseThrow();
        assertThat(entity.getSnapshotJson()).isEqualTo(original);
        assertThat(entity.getSnapshotHistoryJson()).isNull();
        verify(codex,never()).summarizeMarket(anyString());
    }

    @Test void invalidRecoverySummaryCannotReplaceAPublishedSnapshotOrAppendHistory(){
        var original=storeStaleToday();
        when(codex.summarizeMarket(anyString())).thenReturn(output.replace("종가는 100","종가는 999"));
        assertThat(service.generateToday().status()).isEqualTo("PARTIAL");
        var entity=repository.findById(date).orElseThrow();
        assertThat(entity.getSnapshotJson()).isEqualTo(original);
        assertThat(entity.getSnapshotHistoryJson()).isNull();
    }

    @Test void aPublicationChangedDuringSummaryIsReusedInsteadOfOverwritingItsEvidence(){
        var previous=json.stored(storeStaleToday(),MarketBriefing.class);
        var changedNews=new MarketBriefing.Source(news.id(),news.kind(),news.label(),"그 사이 갱신한 기사",
                news.url(),news.observedAt(),null,null,null);
        String changed=json.write(new MarketBriefing(date,"READY",previous.cutoffAt(),clock.instant(),clock.instant(),
                "updated-model",List.of(),List.of(previous.sources().get(0),changedNews),List.of(),null));
        when(codex.summarizeMarket(anyString())).thenAnswer(call->{
            var entity=repository.findById(date).orElseThrow();
            entity.setSnapshotJson(changed);repository.saveAndFlush(entity);return output;
        });

        var result=service.generateToday();

        assertThat(result.model()).isEqualTo("updated-model");
        assertThat(result.sources()).containsExactly(previous.sources().get(0),changedNews);
        var entity=repository.findById(date).orElseThrow();
        assertThat(entity.getSnapshotJson()).isEqualTo(changed);
        assertThat(entity.getSnapshotHistoryJson()).isNull();
    }

    @Test void serializationFailureDoesNotMutateTheManagedSnapshotOrItsHistory(){
        var original=storeStaleToday();
        var failingJson=spy(json);
        doThrow(new IllegalStateException("synthetic serialization failure")).when(failingJson).write(isA(MarketBriefing.class));
        var failingService=new MarketBriefingService(repository,sources,calendar,codex,failingJson,clock,transactionManager);
        assertThatThrownBy(failingService::generateToday).isInstanceOf(IllegalStateException.class);
        var entity=repository.findById(date).orElseThrow();
        assertThat(entity.getSnapshotJson()).isEqualTo(original);
        assertThat(entity.getSnapshotHistoryJson()).isNull();
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void flushedWriteFailureRollsBackBothThePublishedBodyAndItsHistory(){
        var original=storeStaleToday();
        try{
            var failingRepository=mock(MarketBriefingRepository.class,delegatesTo(repository));
            doAnswer(call->{repository.saveAndFlush(call.getArgument(0));throw new IllegalStateException("synthetic failure after flush");})
                    .when(failingRepository).saveAndFlush(any(MarketBriefingEntity.class));
            var failingService=new MarketBriefingService(failingRepository,sources,calendar,codex,json,clock,transactionManager);
            assertThatThrownBy(failingService::generateToday).isInstanceOf(IllegalStateException.class);
            var stored=repository.findById(date).orElseThrow();
            assertThat(stored.getSnapshotJson()).isEqualTo(original);
            assertThat(stored.getSnapshotHistoryJson()).isNull();
        }finally{repository.deleteById(date);}
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void competingCommittedPublicationSurvivesTheLosingOptimisticWrite(){
        var original=storeStaleToday();
        try{
            var winningSnapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                    clock.instant(),clock.instant(),"winning-model",List.of(),List.of(quote,news),List.of(),null);
            String winningJson=json.write(winningSnapshot);
            String winningHistory=json.write(List.of(original));
            var otherTransaction=new TransactionTemplate(transactionManager);
            otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            var competingRepository=mock(MarketBriefingRepository.class,delegatesTo(repository));
            doAnswer(call->{
                otherTransaction.execute(status->{
                    var winner=repository.findById(date).orElseThrow();
                    winner.setSnapshotJson(winningJson);winner.setSnapshotHistoryJson(winningHistory);
                    repository.saveAndFlush(winner);return null;
                });
                return repository.saveAndFlush(call.getArgument(0));
            }).when(competingRepository).saveAndFlush(any(MarketBriefingEntity.class));
            var competingService=new MarketBriefingService(competingRepository,sources,calendar,codex,json,clock,transactionManager);
            assertThatThrownBy(competingService::generateToday).isInstanceOf(OptimisticLockingFailureException.class);
            var stored=repository.findById(date).orElseThrow();
            assertThat(stored.getSnapshotJson()).isEqualTo(winningJson);
            assertThat(stored.getSnapshotHistoryJson()).isEqualTo(winningHistory);
        }finally{repository.deleteById(date);}
    }

    private String storeTodaySources(List<MarketBriefing.Source> evidence){
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant().minusSeconds(60),clock.instant().minusSeconds(70),"test-model",List.of(),evidence,List.of(),null);
        String saved=json.write(snapshot);
        var entity=repository.findById(date).orElseGet(()->new MarketBriefingEntity(date,saved));
        entity.setSnapshotJson(saved);repository.saveAndFlush(entity);
        return saved;
    }

    private String storeStaleToday(){
        var oldQuote=new MarketBriefing.Source(quote.id(),quote.kind(),quote.label(),quote.title(),quote.url(),
                Instant.parse("2026-11-03T06:30:00Z"),quote.value(),quote.changeRate(),quote.unit());
        var snapshot=new MarketBriefing(date,"READY",date.atTime(8,30).atZone(MarketBriefingService.KST).toInstant(),
                clock.instant().minusSeconds(60),clock.instant().minusSeconds(70),"test-model",List.of(),List.of(oldQuote,news),List.of(),null);
        String saved=json.write(snapshot);
        repository.saveAndFlush(new MarketBriefingEntity(date,saved));
        return saved;
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
        var monday=new MarketBriefingService(repository,sources,calendar,codex,json,mondayClock,transactionManager);
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

    @Test void realProviderComparisonDateCanBeSummarizedInKorean(){
        var parsed=new MarketBriefingSources(mock(PublicMarketHttp.class),new ObjectMapper()).parseNaverQuote("""
                {"priceInfos":[{"localDate":"20261103","closePrice":100},
                 {"localDate":"20261104","closePrice":110}]}
                ""","KOSPI","코스피","pt",Instant.parse("2026-11-04T23:30:00Z"));
        when(sources.collect(any())).thenReturn(new MarketBriefing.Input(clock.instant(),List.of(parsed),List.of()));
        when(codex.summarizeMarket(anyString())).thenReturn("""
                {"items":[{"title":"국내장","summary":"코스피 종가는 110이며 11월 3일 자료 대비 10%입니다.","sourceIds":["KOSPI"]}]}
                """);
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
        var tomorrow=new MarketBriefingService(repository,sources,calendar,codex,json,Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneId.of("Asia/Seoul")),transactionManager);
        assertThat(tomorrow.latest().date()).isEqualTo(date);
    }

    @Test void closedDayDoesNotCollectDataOrGenerateAnAiCall(){
        when(calendar.isTradingDay(date)).thenReturn(false);
        assertThat(service.generateToday().status()).isEqualTo("CLOSED");
        verifyNoInteractions(sources,codex);
        assertThat(repository.count()).isZero();
    }

    @Test void scheduledGenerationWaitsUntil0830AndRetriesOnlyThreeTimes(){
        var morning=new MarketBriefingService(repository,sources,calendar,codex,json,Clock.fixed(Instant.parse("2026-11-04T23:29:00Z"),ZoneId.of("Asia/Seoul")),transactionManager);
        morning.generateIfDue();
        verifyNoInteractions(sources,codex);
        when(codex.summarizeMarket(anyString())).thenThrow(new AssistantException("CODEX_TIMEOUT"));
        for(int i=0;i<5;i++)service.generateIfDue();
        verify(codex,times(3)).summarizeMarket(anyString());
        doReturn(output).when(codex).summarizeMarket(anyString());
        assertThat(service.generateToday().status()).isEqualTo("READY"); // explicit retry remains available.
    }
}
