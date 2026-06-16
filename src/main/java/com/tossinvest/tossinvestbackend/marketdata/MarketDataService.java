package com.tossinvest.tossinvestbackend.marketdata;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 시세 데이터(현재가/호가/체결) 조회 서비스.
 * TODO: 응답 구조는 추정이며, 실제 가이드 문서 확인 시 보정 필요.
 */
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final TossApiClient apiClient;

    /**
     * symbols: 콤마로 구분된 종목 심볼 (다건 가능)
     */
    public PriceResponse getPrices(String symbols) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/prices")
                        .queryParam("symbols", symbols)
                        .build(),
                PriceResponse.class
        );
    }

    /**
     * symbol: 단일 종목 심볼
     */
    public OrderbookResponse getOrderbook(String symbol) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/orderbook")
                        .queryParam("symbol", symbol)
                        .build(),
                OrderbookResponse.class
        );
    }

    /**
     * symbol: 단일 종목 심볼, count: 조회 건수
     */
    public TradeResponse getTrades(String symbol, int count) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/trades")
                        .queryParam("symbol", symbol)
                        .queryParam("count", count)
                        .build(),
                TradeResponse.class
        );
    }
}
