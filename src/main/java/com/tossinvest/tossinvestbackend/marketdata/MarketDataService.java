package com.tossinvest.tossinvestbackend.marketdata;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 시세 데이터(현재가/호가/체결) 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final TossApiClient apiClient;

    public PriceResponse getPrices(String symbols) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/prices")
                        .queryParam("symbols", symbols)
                        .build(),
                PriceResponse.class
        );
    }

    public OrderbookResponse getOrderbook(String symbol) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/orderbook")
                        .queryParam("symbol", symbol)
                        .build(),
                OrderbookResponse.class
        );
    }

    public TradeResponse getTrades(String symbol, int count) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/trades")
                        .queryParam("symbol", symbol)
                        .queryParam("count", count)
                        .build(),
                TradeResponse.class
        );
    }

    /**
     * 일봉 캔들 조회 (최신부터, before 없음).
     */
    public CandleResponse getCandles(String symbol, String interval, int count) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/candles")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("count", count)
                        .queryParam("adjusted", true)
                        .build(),
                CandleResponse.class
        );
    }

    /**
     * 일봉 캔들 조회 - before 날짜시간 지정 (페이지네이션용).
     * before는 "YYYY-MM-DDTHH:mm:ss+09:00" 형식.
     * queryParam 경유 시 '+' 이중 인코딩 문제가 있어 URI 문자열을 직접 조립해 호출한다.
     * '+09:00'의 '+'를 '%2B'로 수동 대체해 정확한 URL을 구성한다.
     */
    public CandleResponse getCandlesWithDateBefore(String symbol, String interval, int count, String beforeDatetime) {
        // '+09:00'의 '+' → '%2B' 수동 치환 후 전체를 path 문자열에 직접 삽입
        String encodedBefore = beforeDatetime.replace("+", "%2B");
        String uri = "/api/v1/candles?symbol=" + symbol
                + "&interval=" + interval
                + "&count=" + count
                + "&adjusted=true"
                + "&before=" + encodedBefore;
        return apiClient.get(uri, CandleResponse.class);
    }
}
