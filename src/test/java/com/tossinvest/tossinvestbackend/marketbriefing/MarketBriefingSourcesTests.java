package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

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

    @Test void officialExchangeCalendarIncludesActualHolidaysAndRejectsMissingYears(){
        String json="{\"block1\":[{\"calnd_dd\":\"2026-05-01\",\"holdy_nm\":\"근로자의날\"},{\"calnd_dd\":\"2026-12-31\",\"holdy_nm\":\"연말휴장\"}]}";
        var dates=MarketTradingCalendar.parseHolidays(json,2026,new ObjectMapper());
        assertThat(dates).containsExactlyInAnyOrder(LocalDate.of(2026,5,1),LocalDate.of(2026,12,31));
        assertThatThrownBy(()->MarketTradingCalendar.parseHolidays(json,2027,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
}
