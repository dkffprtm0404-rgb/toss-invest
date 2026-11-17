package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketBriefingSourcesTests {
    final MarketBriefingSources sources=new MarketBriefingSources(new PublicMarketHttp(),new ObjectMapper());
    final Instant cutoff=Instant.parse("2026-11-04T23:30:00Z");

    @Test void rssKeepsOnlyRecentDatedArticlesAndRejectsUnsafeUrlsAndFutureItems(){
        String rss="""
            <rss><channel>
             <item><title>유효한 경제 뉴스</title><link>https://example.com/article</link><pubDate>Wed, 04 Nov 2026 23:00:00 GMT</pubDate></item>
             <item><title>잘못된 링크</title><link>javascript:alert(1)</link><pubDate>Wed, 04 Nov 2026 23:00:00 GMT</pubDate></item>
             <item><title>미래 뉴스</title><link>https://example.com/future</link><pubDate>Thu, 05 Nov 2026 01:00:00 GMT</pubDate></item>
             <item><title>오래된 뉴스</title><link>https://example.com/old</link><pubDate>Mon, 02 Nov 2026 01:00:00 GMT</pubDate></item>
             <item><title>시각 없는 뉴스</title><link>https://example.com/undated</link></item>
            </channel></rss>
            """;
        assertThat(sources.parseRss(rss,"test","test",cutoff)).singleElement().satisfies(s->{
            assertThat(s.title()).isEqualTo("유효한 경제 뉴스");
            assertThat(s.url()).isEqualTo("https://example.com/article");
        });
    }

    @Test void rssCannotResolveAnExternalEntity(){
        String xml="<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///C:/Windows/win.ini'>]><rss><channel><item><title>&secret;</title></item></channel></rss>";
        assertThatThrownBy(()->sources.parseRss(xml,"test","test",cutoff)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void quoteUsesTheLastCompletedSessionBeforeTheCutoffAndComputesItsOwnChange(){
        // At 08:30 KST the November 5 domestic session has not opened.
        String chart="""
          {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Seoul"},
          "timestamp":[1793664000,1793750400,1793836800],
          "indicators":{"quote":[{"close":[100,110,999]}]}}],"error":null}}
          """;
        var quote=sources.parseQuote(chart,"KOSPI","코스피","^KS11","pt",cutoff);
        assertThat(quote.value()).isEqualByComparingTo("110");
        assertThat(quote.changeRate()).isEqualByComparingTo("0.1");
        assertThat(quote.observedAt()).isEqualTo(Instant.parse("2026-11-04T06:30:00Z"));
    }

    @Test void domesticIndicesUseNovember10InsteadOfAnOlderYahooSession(){
        var http=mock(PublicMarketHttp.class);
        for(String id:new String[]{"KOSPI","KOSDAQ"}){
            when(http.get(URI.create("https://api.stock.naver.com/chart/domestic/index/"+id+"?periodType=dayCandle")))
                    .thenReturn("""
                        {"priceInfos":[{"localDate":"20261111","closePrice":999},
                        {"localDate":"20261109","closePrice":100},
                        {"localDate":"20261110","closePrice":110}]}
                        """);
        }
        var input=new MarketBriefingSources(http,new ObjectMapper()).collect(Instant.parse("2026-11-10T23:30:00Z"));
        assertThat(input.sources()).hasSize(2).allSatisfy(source->{
            assertThat(source.value()).isEqualByComparingTo("110");
            assertThat(source.changeRate()).isEqualByComparingTo("0.1");
            assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-10T06:30:00Z"));
            assertThat(source.url()).startsWith("https://m.stock.naver.com/");
        });
    }

    @Test void exchangeRateUsesThePreviousKoreanDateAndItsOwnProvidersPreviousPrice(){
        String body="""
                [{"localTradedAt":"2026-11-11","closePrice":"9,999.00"},
                 {"localTradedAt":"2026-11-10","closePrice":"1,344.50"},
                 {"localTradedAt":"2026-11-09","closePrice":"1,342.00"}]
                """;
        var source=sources.parseNaverQuote(body,"USDKRW","원/달러","KRW",Instant.parse("2026-11-10T23:30:00Z"));
        assertThat(source.value()).isEqualByComparingTo("1344.50");
        assertThat(source.changeRate()).isEqualByComparingTo("0.0018628912");
        assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-10T14:59:59Z"));
        assertThat(source.title()).contains("일별 환율");
        assertThat(source.url()).contains("FX_USDKRW");
    }

    @Test void aSingleOrInvalidNaverPriceDoesNotProduceAnInventedDailyChange(){
        String body="""
                {"priceInfos":[{"localDate":"20261109","closePrice":0},
                {"localDate":"20261110","closePrice":110}]}
                """;
        assertThatThrownBy(()->sources.parseNaverQuote(body,"KOSPI","코스피","pt",Instant.parse("2026-11-10T23:30:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void collectionActuallyRoutesExchangeRatesToTheKoreanDailyFeed(){
        var http=mock(PublicMarketHttp.class);
        when(http.get(URI.create("https://api.stock.naver.com/marketindex/exchange/FX_USDKRW/prices?page=1&pageSize=10")))
                .thenReturn("""
                        [{"localTradedAt":"2026-11-11","closePrice":"9,999.00"},
                         {"localTradedAt":"2026-11-10","closePrice":"1,344.50"},
                         {"localTradedAt":"2026-11-09","closePrice":"1,342.00"}]
                        """);
        var input=new MarketBriefingSources(http,new ObjectMapper()).collect(Instant.parse("2026-11-10T23:30:00Z"));
        assertThat(input.sources()).singleElement().satisfies(source->{
            assertThat(source.id()).isEqualTo("USDKRW");
            assertThat(source.value()).isEqualByComparingTo("1344.50");
            assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-10T14:59:59Z"));
        });
    }

    @Test void aMissingIntermediateSessionNamesTheActualComparisonDate(){
        for(String rows:new String[]{"""
                [{"localDate":"20261106","closePrice":100},
                 {"localDate":"20261109","closePrice":null},
                 {"localDate":"20261110","closePrice":110}]
                ""","""
                [{"localDate":"20261106","closePrice":100},
                 {"localDate":"20261110","closePrice":110}]
                """}){
            var source=sources.parseNaverQuote("{\"priceInfos\":"+rows+"}","KOSPI","코스피","pt",Instant.parse("2026-11-10T23:30:00Z"));
            assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-10T06:30:00Z"));
            assertThat(source.changeRate()).isEqualByComparingTo("0.1");
            assertThat(source.title()).contains("2026년 11월 6일 자료 대비").doesNotContain("전거래일 대비");
        }
    }

    @Test void naverFailureKeepsTheAvailableYahooEvidenceAndIdentifiesTheFallback(){
        var http=mock(PublicMarketHttp.class);
        when(http.get(URI.create("https://api.stock.naver.com/chart/domestic/index/KOSPI?periodType=dayCandle")))
                .thenThrow(new IllegalStateException("unavailable"));
        when(http.get(URI.create("https://query1.finance.yahoo.com/v8/finance/chart/%5EKS11?range=10d&interval=1d")))
                .thenReturn("""
                        {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Seoul"},
                        "timestamp":[1793664000,1793750400],"indicators":{"quote":[{"close":[100,110]}]}}]}}
                        """);
        var input=new MarketBriefingSources(http,new ObjectMapper()).collect(cutoff);
        assertThat(input.sources()).singleElement().satisfies(source->{
            assertThat(source.id()).isEqualTo("KOSPI");
            assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-04T06:30:00Z"));
            assertThat(source.value()).isEqualByComparingTo("110");
        });
        assertThat(input.warnings()).anySatisfy(warning->assertThat(warning).contains("코스피","Yahoo"));
    }

    @Test void officialExchangeCalendarIncludesActualHolidaysAndRejectsMissingYears(){
        String json="{\"block1\":[{\"calnd_dd\":\"2026-05-01\",\"holdy_nm\":\"근로자의날\"},{\"calnd_dd\":\"2026-12-31\",\"holdy_nm\":\"연말휴장\"}]}";
        var dates=MarketTradingCalendar.parseHolidays(json,2026,new ObjectMapper());
        assertThat(dates).containsExactlyInAnyOrder(LocalDate.of(2026,5,1),LocalDate.of(2026,12,31));
        assertThatThrownBy(()->MarketTradingCalendar.parseHolidays(json,2027,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidKoreanExchangeRateFallsBackWithoutChangingTheYahooReferenceTime(){
        var http=mock(PublicMarketHttp.class);
        when(http.get(URI.create("https://api.stock.naver.com/marketindex/exchange/FX_USDKRW/prices?page=1&pageSize=10")))
                .thenReturn("[{\"localTradedAt\":\"2026-11-04\",\"closePrice\":\"1300\"}]");
        when(http.get(URI.create("https://query1.finance.yahoo.com/v8/finance/chart/KRW%3DX?range=10d&interval=1d")))
                .thenReturn("""
                        {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Europe/London"},
                        "timestamp":[%d,%d],"indicators":{"quote":[{"close":[100,110]}]}}]}}
                        """.formatted(Instant.parse("2026-11-02T00:00:00Z").getEpochSecond(),
                        Instant.parse("2026-11-03T00:00:00Z").getEpochSecond()));

        var input=new MarketBriefingSources(http,new ObjectMapper()).collect(cutoff);

        assertThat(input.sources()).singleElement().satisfies(source->{
            assertThat(source.id()).isEqualTo("USDKRW");
            assertThat(source.value()).isEqualByComparingTo("110");
            assertThat(source.changeRate()).isEqualByComparingTo("0.1");
            assertThat(source.observedAt()).isEqualTo(Instant.parse("2026-11-04T00:00:00Z"));
            assertThat(source.url()).startsWith("https://finance.yahoo.com/quote/");
        });
        assertThat(input.warnings()).anySatisfy(warning->assertThat(warning).contains("원/달러","Yahoo"));
    }

    @Test void failureOfBothQuoteProvidersDoesNotInventAnyExchangeRate(){
        var http=mock(PublicMarketHttp.class);
        when(http.get(any(URI.class))).thenThrow(new IllegalStateException("synthetic provider outage"));
        var input=new MarketBriefingSources(http,new ObjectMapper()).collect(cutoff);
        assertThat(input.sources()).isEmpty();
        assertThat(input.warnings()).anySatisfy(warning->assertThat(warning).contains("원/달러","수집하지 못했습니다"));
    }
}
