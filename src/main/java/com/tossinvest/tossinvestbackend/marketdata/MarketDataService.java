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
     * before를 URI 템플릿 값으로 전달하여 시간대의 '+'를 정확히 한 번 인코딩한다.
     */
    public CandleResponse getCandlesWithDateBefore(String symbol, String interval, int count, String beforeDatetime) {
        return apiClient.get(uriBuilder -> uriBuilder.path("/api/v1/candles")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("count", count)
                .queryParam("adjusted", true)
                .queryParam("before", "{before}")
                .build(beforeDatetime), CandleResponse.class);
    }
}
