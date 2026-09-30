package com.tossinvest.tossinvestbackend.strategypaper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.marketdata.*;
import com.tossinvest.tossinvestbackend.stockinfo.StockInfoService;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PaperMarketDataTests {
    final MarketDataService source=mock(MarketDataService.class);
    final PaperMarketData market=new PaperMarketData(source,mock(StockInfoService.class));
    CandleResponse response(String currency) throws Exception {return new ObjectMapper().readValue("""
        {"result":{"candles":[
        {"timestamp":"2025-01-03T00:00:00+09:00","currency":"%s","openPrice":"101","highPrice":"101","lowPrice":"101","closePrice":"101","volume":"10"},
        {"timestamp":"2025-01-02T00:00:00+09:00","currency":"%s","openPrice":"100","highPrice":"100","lowPrice":"100","closePrice":"100","volume":"10"}]}}
        """.formatted(currency,currency),CandleResponse.class);}
    @Test void excludesUnfinishedTodayAndValidatesCurrencyAndContinuity() throws Exception {
        when(source.getCandles("005930","1d",200)).thenReturn(response("KRW"));
        var bars=market.fetch("005930",null,Instant.parse("2025-01-03T05:00:00Z"));assertThat(bars).hasSize(1);assertThat(bars.get(0).close()).isEqualByComparingTo("100");
        assertThatThrownBy(()->market.fetch("005930",LocalDate.of(2025,1,1),Instant.parse("2025-01-04T00:00:00Z"))).hasMessageContaining("누락");
        when(source.getCandles("005930","1d",200)).thenReturn(response("USD"));
        assertThatThrownBy(()->market.fetch("005930",null,Instant.parse("2025-01-04T00:00:00Z"))).hasMessageContaining("원화");
    }
}
