package com.tossinvest.tossinvestbackend.stockinfo;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 종목 정보 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class StockInfoService {

    private final TossApiClient apiClient;

    /**
     * symbols: 콤마로 구분된 종목 심볼 문자열 (필수). 예: "005930" 또는 "005930,AAPL"
     */
    public StockResponse getStocks(String symbols) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/stocks")
                        .queryParam("symbols", symbols)
                        .build(),
                StockResponse.class
        );
    }
}
